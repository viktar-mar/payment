// The session capability enters only over stdin. The browser uses a fresh, nonpersistent profile.
import {chromium} from '@playwright/test';
import {pathToFileURL} from 'node:url';
import {setTimeout as delay} from 'node:timers/promises';

export function inputs(session, merchant) {
  if (typeof session !== 'string' || session.length > 2048 ||
      !/^https:\/\/billing\.stripe\.com\/p\/session(?:\/flow)?\?secret=test_[A-Za-z0-9_-]+$/.test(session)) throw Error('SESSION');
  if (typeof merchant !== 'string' || !/^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}\/portal-return\/[a-f0-9-]{36}$/.test(merchant) ||
      new URL(merchant).href !== merchant) throw Error('RETURN');
}

export function allowedProvider(url) {
  const uri = new URL(url);
  return uri.protocol === 'https:' && !uri.username && !uri.password && !uri.port &&
    ['stripe.com', 'stripecdn.com', 'stripe.network'].some(host => uri.hostname === host || uri.hostname.endsWith('.' + host));
}

// The optional diagnostic receives only predefined observations. It cannot change routing or clicks.
const diagnosticCodes = new Set([
  'SCRIPT_OK','SCRIPT_HTTP_ERROR','SCRIPT_FAILED','FETCH_OK','FETCH_HTTP_ERROR','FETCH_FAILED',
  'PROVIDER_WRITE_BLOCKED','PROVIDER_PREFLIGHT_BLOCKED','FOREIGN_BLOCKED','FRAME_BLOCKED',
  'NAVIGATION_BLOCKED','PAGE_ERROR','DIAGNOSTIC_UNAVAILABLE','DOM_RETURN_EXACT',
  'DOM_ORIGINAL_DOCUMENT','DOM_DOCUMENT_CHANGED',
  'DOM_RETURN_ONE','DOM_RETURN_MULTIPLE','ANCHOR_EVALUATION_FAILED','ANCHOR_TIMEOUT'
]);
const returnDiagnosticCodes = new Set([
  'TARGET_SOURCE','TARGET_POPUP','POPUP_CREATED','SOURCE_COMMITTED_RETURN','MERCHANT_GET_SEEN',
  'MERCHANT_REQUEST_BLOCKED','MERCHANT_UPGRADE_SEEN','TRUSTED_CLICK_SEEN','TRUSTED_CLICK_DENIED',
  'WITNESS_FAILED','MERCHANT_STATUS_OK','MERCHANT_STATUS_ERROR','MERCHANT_FETCH_FAILED',
  'CLICK_FINISHED','CLICK_FAILED','RECEIVER_URL_COMMITTED','RETURN_NODE_CONNECTED','RETURN_NODE_DETACHED',
  'RETURN_NODE_COVERED','RETURN_NODE_HITTABLE'
]);

async function observeDocument(page, merchant, owner, observe) {
  const codes = await page.evaluate(({merchant, owner}) => {
    const observations = new Set();
    observations.add(window.document === owner ? 'DOM_ORIGINAL_DOCUMENT' : 'DOM_DOCUMENT_CHANGED');
    const exact = Array.from(document.querySelectorAll('a[href]')).filter(anchor => {
      const rect = anchor.getBoundingClientRect();
      return anchor.href === merchant && rect.width > 0 && rect.height > 0 && getComputedStyle(anchor).visibility === 'visible';
    }).length;
    if (exact) {
      observations.add('DOM_RETURN_EXACT');
      observations.add(exact === 1 ? 'DOM_RETURN_ONE' : 'DOM_RETURN_MULTIPLE');
    }
    return Array.from(observations);
  }, {merchant, owner});
  for (const code of codes) observe(code);
}

export async function visit(session, merchant, fetchResponse = (route, options) => route.fetch(options), diagnostic, returnDetails=false) {
  let stage = 'INPUT', browser;
  const codes = new Set();
  const observe = code => {
    if (diagnostic && (diagnosticCodes.has(code) || returnDetails && returnDiagnosticCodes.has(code))) codes.add(code);
  };
  try {
    inputs(session, merchant);
    stage = 'BROWSER_LAUNCH';
    browser = await chromium.launch({channel:'chrome', headless:true, timeout:15000,
      args:['--disable-background-networking','--disable-default-apps','--no-pings','--proxy-server=direct://','--host-resolver-rules=MAP * ~NOTFOUND']});
    const context = await browser.newContext({serviceWorkers:'block', acceptDownloads:false});
    const page = await context.newPage();
    page.setDefaultTimeout(30000);
    page.on('pageerror', () => observe('PAGE_ERROR'));
    page.on('popup', () => observe('POPUP_CREATED'));
    page.on('framenavigated', frame => {
      if (frame === page.mainFrame() && frame.url() === merchant) observe('SOURCE_COMMITTED_RETURN');
    });
    page.on('requestfailed', req => {
      if (req.resourceType() === 'script') observe('SCRIPT_FAILED');
      if (['fetch','xhr'].includes(req.resourceType())) observe('FETCH_FAILED');
    });
    let hosted = false, clickEvidence, popupReturn = false, returnAttempted = false, returnStarted = false, returned = false, nextDocument;
    await context.routeWebSocket('**/*', socket => socket.close());
    async function handleRequest(route) {
      const req = route.request();
      const url = req.url();
      const back = url === merchant;
      if (back) returnAttempted = true;
      if (back && req.method() === 'GET') observe('MERCHANT_GET_SEEN');
      if (url === merchant.replace(/^http:/,'https:')) observe('MERCHANT_UPGRADE_SEEN');
      const navigation = req.isNavigationRequest();
      let frame;
      // Chromium can report a new tab's first navigation before its Frame exists.
      try { frame = req.frame(); } catch {}
      const main = navigation && frame === page.mainFrame();
      const popupMain = popupReturn && navigation && (!frame || frame.page() !== page && frame === frame.page().mainFrame());
      if (diagnostic) {
        if (req.method() !== 'GET' && allowedProvider(url)) observe(req.method() === 'OPTIONS'
          ? 'PROVIDER_PREFLIGHT_BLOCKED' : 'PROVIDER_WRITE_BLOCKED');
        if (!back && !allowedProvider(url)) observe('FOREIGN_BLOCKED');
        if ((!frame || frame.page() !== page) && !(back && popupMain)) observe('FRAME_BLOCKED');
        if (!back && main && hosted) observe('NAVIGATION_BLOCKED');
      }
      if (req.method() !== 'GET' || req.postData() != null ||
          (back ? !(popupReturn ? popupMain : main) || !hosted || !clickEvidence || returnStarted :
            !frame || frame.page() !== page || !allowedProvider(url) ||
            main && (hosted || new URL(url).hostname !== 'billing.stripe.com'))) {
        if (back) observe('MERCHANT_REQUEST_BLOCKED');
        return route.abort();
      }
      // Check every redirect before handing it to Chrome, including subresource redirects.
      if (back) {
        if (!(await Promise.race([clickEvidence, delay(500).then(() => false)])) || returnStarted) {
          observe('TRUSTED_CLICK_DENIED'); return route.abort();
        }
        returnStarted = true;
      }
      let headers = {...req.headers()};
      if (back) delete headers.referer;
      let target = url;
      for (let redirects = 0; redirects <= 5; redirects++) {
        const response = await fetchResponse(route,{url:target, headers, maxRedirects:0, maxRetries:0, timeout:15000})
          .catch(failure => { if (back) observe('MERCHANT_FETCH_FAILED'); throw failure; });
        try {
          if ([301,302,303,307,308].includes(response.status())) {
            const location = response.headers().location;
            if (!location || back) return route.abort();
            const dest = new URL(location,target);
            if (!allowedProvider(dest.href)) return route.abort();
            if (main) {
              // Playwright routes only the first request of a browser-followed redirect.
              // Issue a separately intercepted navigation to the exact verified Location.
              if (hosted || dest.origin !== 'https://billing.stripe.com') return route.abort();
              nextDocument = dest.href;
              // A 204 leaves the current document in place. Aborting starts Chrome's error
              // document, which can interrupt the next approved navigation after it begins.
              return route.fulfill({status:204,body:''});
            }
            if (dest.origin !== new URL(target).origin)
              headers = Object.fromEntries(Object.entries(headers).filter(([name])=>['accept','accept-language','user-agent'].includes(name)));
            target = dest.href;
            continue;
          }
          if (back) {
            if (response.status() !== 200) { observe('MERCHANT_STATUS_ERROR'); return route.abort(); }
            returned = true;
            observe('MERCHANT_STATUS_OK');
          }
          if (req.resourceType() === 'script') observe(response.ok() ? 'SCRIPT_OK' : 'SCRIPT_HTTP_ERROR');
          if (['fetch','xhr'].includes(req.resourceType())) observe(response.ok() ? 'FETCH_OK' : 'FETCH_HTTP_ERROR');
          return await route.fulfill({response});
        } finally { await response.dispose(); }
      }
      return route.abort();
    }
    await context.route('**/*', route => handleRequest(route).catch(() => route.abort().catch(() => {})));
    stage = 'HOSTED_DOCUMENT';
    let opened, target = session;
    const visited = new Set();
    while (visited.size < 6 && !visited.has(target)) {
      visited.add(target);
      nextDocument = undefined;
      try { opened = await page.goto(target,{waitUntil:'domcontentloaded'}); }
      catch (failure) { if (!nextDocument) throw failure; }
      if (!nextDocument) break;
      target = nextDocument;
    }
    if (!opened?.ok() || new URL(page.url()).origin !== 'https://billing.stripe.com') throw Error('HOSTED');
    const document = await page.evaluateHandle(() => window.document);
    const documentClicks = await document.evaluateHandle(owner => {
      const state = {count:0};
      owner.addEventListener('click', event => { if (event.isTrusted) state.count++; }, {capture:true});
      return state;
    });
    hosted = true;
    const deadline = Date.now() + 30000;
    for (let selection = 0; selection < 4; selection++) {
      stage = 'RETURN_LINK';
      // Stripe documents return_url as the destination of the link back to the merchant.
      // Select only that destination, without consulting localized labels or collecting the page HTML.
      let link;
      try {
        do {
          // Responsive layouts may render multiple controls for the same merchant destination.
          // Choose one visible anchor; the same node and destination are checked again on click.
          const candidate = await page.evaluateHandle(({owner, merchant}) => {
            if (window.document !== owner) return null;
            const matches = Array.from(owner.querySelectorAll('a[href]')).filter(anchor => {
              const rect = anchor.getBoundingClientRect();
              return anchor.href === merchant && anchor.isConnected && rect.width > 0 && rect.height > 0 &&
                getComputedStyle(anchor).visibility === 'visible';
            });
            return matches.find(anchor => {
              const rect=anchor.getBoundingClientRect();
              return anchor.contains(owner.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2));
            }) || matches[0] || null;
          }, {owner:document,merchant});
          link = candidate.asElement();
          if (link) break;
          await candidate.dispose();
          await delay(150);
        } while (Date.now() < deadline);
        if (!link) { observe('ANCHOR_TIMEOUT'); throw Error('RETURN_LINK'); }
      }
      catch (failure) {
        if (diagnostic) {
          if (!codes.has('ANCHOR_TIMEOUT')) observe('ANCHOR_EVALUATION_FAILED');
          try { await observeDocument(page,merchant,document,observe); } catch { observe('DIAGNOSTIC_UNAVAILABLE'); }
        }
        throw failure;
      }
      // The listener resolves on a real click at this exact anchor in this document.
      // Keep the original link navigation and its default browser behavior.
      const targetMode = await link.evaluate(element => (element.target ||
        element.ownerDocument.querySelector('base[target]')?.target || '_self').toLowerCase());
      if (!['_self','_top','_parent','_blank'].includes(targetMode)) throw Error('RETURN_LINK');
      popupReturn = targetMode === '_blank';
      observe(popupReturn ? 'TARGET_POPUP' : 'TARGET_SOURCE');
      const witness = await link.evaluateHandle((element, {owner, merchant, documentClicks}) => {
        if (window.document !== owner || element.ownerDocument !== owner || element.href !== merchant) throw Error('RETURN_LINK');
        const evidence = {};
        evidence.click = new Promise(resolve => element.addEventListener('click', event => {
          resolve(window.document === owner && element.ownerDocument === owner && element.href === merchant && element.isConnected &&
            documentClicks.count === 1 && event.isTrusted && event.button === 0 && event.composedPath().includes(element));
        }, {once:true,capture:true}));
        return evidence;
      }, {owner:document,merchant,documentClicks});
      // Register the witness before navigation starts. Evaluating in the old document
      // after a top-level request begins can wait for the very response being held.
      clickEvidence = witness.evaluate(value => value.click).then(trusted => {
        observe(trusted ? 'TRUSTED_CLICK_SEEN' : 'TRUSTED_CLICK_DENIED'); return trusted;
      }).catch(() => { observe('WITNESS_FAILED'); return false; });
      stage = 'RETURN_COMMIT';
      const arrival = popupReturn ? page.waitForEvent('popup') : Promise.resolve(page);
      const clicked = link.click().then(() => observe('CLICK_FINISHED')).catch(async failure => {
        observe('CLICK_FAILED');
        if (diagnostic && returnDetails) {
          try {
            const status = await link.evaluate(element => {
              if (!element.isConnected) return ['RETURN_NODE_DETACHED'];
              const rect=element.getBoundingClientRect();
              const hit=element.ownerDocument.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2);
              return ['RETURN_NODE_CONNECTED',element.contains(hit) ? 'RETURN_NODE_HITTABLE' : 'RETURN_NODE_COVERED'];
            });
            for (const code of status) observe(code);
          } catch { observe('DIAGNOSTIC_UNAVAILABLE'); }
        }
        throw failure;
      });
      let recipient;
      try { [recipient] = await Promise.all([arrival,clicked]); }
      catch (failure) {
        // A hydrated document may replace its initially visible anchor before Chrome
        // dispatches a click. Reselect only a demonstrably detached, unclicked node.
        const untouched = await documentClicks.evaluate((value, {element,owner}) =>
          window.document === owner && element.ownerDocument === owner && !element.isConnected && value.count === 0,
          {element:link,owner:document}).catch(() => false);
        if (!untouched || returnAttempted || returnStarted || context.pages().length !== 1 ||
            selection === 3 || Date.now() >= deadline) throw failure;
        clickEvidence = undefined;
        continue;
      }
      await recipient.waitForURL(url => url.href === merchant, {waitUntil:'domcontentloaded'});
      observe('RECEIVER_URL_COMMITTED');
      if (!returned || recipient.url() !== merchant || context.pages().length !== (popupReturn ? 2 : 1) ||
          popupReturn && (recipient === page || recipient.context() !== context || new URL(page.url()).origin !== 'https://billing.stripe.com'))
        throw Error('RETURN');
      return;
    }
  } catch { throw Error(stage); }
  finally {
    if (diagnostic) diagnostic([...codes].sort());
    if (browser) {
      try { await browser.close(); } catch { throw Error('CLEANUP'); }
    }
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  try {
    let data = '';
    for await (const chunk of process.stdin) {
      data += chunk;
      if (data.length > 4096) throw Error('INPUT');
    }
    const value = JSON.parse(data);
    if (!Array.isArray(value) || value.length !== 2) throw Error('INPUT');
    const [session, merchant] = value;
    const returnDetails = process.argv[2] === '--diagnose-return';
    const diagnostic = process.argv.length === 3 && (process.argv[2] === '--diagnose' || returnDetails)
      ? codes => console.error('OBSERVED ' + (codes.join(' ') || 'NONE')) : undefined;
    await visit(session, merchant, undefined, diagnostic, returnDetails);
    console.log('HOSTED_RETURN_CONFIRMED');
  } catch (failure) {
    const stages = ['INPUT','BROWSER_LAUNCH','HOSTED_DOCUMENT','RETURN_LINK','RETURN_COMMIT','CLEANUP'];
    console.log(stages.includes(failure.message) ? failure.message : 'INPUT');
    process.exitCode = 1;
  }
}
