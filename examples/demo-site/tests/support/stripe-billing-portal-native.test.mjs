import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawnSync} from 'node:child_process';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {inputs,allowedProvider,visit} from './stripe-billing-portal-native.mjs';

const sample='https://billing.stripe.com/p/session?secret=test_fixture';
const merchant='http://127.0.0.1:8081/portal-return/8a07a955-f709-4175-9bd2-b673e98e1339';

test('a hosted test session returns only to the private merchant endpoint', () => {
  inputs(sample,merchant);
  inputs(sample.replace('/session?', '/session/flow?'),merchant);
  for (const url of [sample.replace('test_', 'live_'), sample+'&next=1',sample.replace('billing.','api.'),sample+'#',
    sample.replace('/session?', '/session/flow/?'),sample.replace('/session?', '/session/other?'),
    sample.replace('/session?', '/session/flow?next=1&')]) {
    assert.throws(() => inputs(url,merchant));
  }
  for (const url of [merchant+'/other',merchant+'?again',merchant.replace('127.0.0.1','localhost'),merchant.replace(':8081',':99999')]) {
    assert.throws(() => inputs(sample,url));
  }
});
test('provider requests require an HTTPS Stripe host without credentials or a custom port', () => {
  for (const url of ['https://billing.stripe.com/','https://b.stripecdn.com/a','https://m.stripe.network/']) assert.equal(allowedProvider(url),true);
  for (const url of ['https://stripe.com.example/','https://notstripe.com','https://u@billing.stripe.com','https://stripe.com:8080/','http://billing.stripe.com','http://127.0.0.1/']) assert.equal(allowedProvider(url),false);
});

test('CLI rejects malformed input using a fixed category without echoing the capability',()=>{
  const script=fileURLToPath(new URL('./stripe-billing-portal-native.mjs',import.meta.url));
  const attempt=spawnSync(process.execPath,[script],{input:JSON.stringify([sample,'invalid']),encoding:'utf8'});
  assert.equal(attempt.status,1);
  assert.equal(attempt.stdout.trim(),'INPUT');
  assert.equal(attempt.stderr,'');
});

const chromeAvailable = existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome') ||
  existsSync('/opt/google/chrome/chrome');

for (const scenario of ['direct','redirect','foreign-redirect','diagnose-hidden-return','responsive-return','popup-return','hydrated-return','trusted-no-retry']) {
  test('Chrome routes the synthetic hosted '+scenario+' case',
    {skip:!chromeAvailable,timeout:60000},async () => {
    const requests=[];
    let endpoint;
    const server=createServer((req,res)=>{
      const path=new URL(req.url,'http://localhost').pathname;
      requests.push({path,method:req.method,referer:req.headers.referer});
      if (scenario==='diagnose-hidden-return' && path==='/p/session') {
        res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end(
          '<title>Hosted flow</title><script src="/bootstrap.js"></script><form>' +
          '<button>Cancel subscription</button></form><a href="/dashboard">Back to account</a>');
      } else if (scenario==='hydrated-return' && path==='/p/session') {
        res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end(
          '<a id="initial" href="'+endpoint+'">Return</a><script>' +
          'const a=document.getElementById("initial"), original=a.addEventListener.bind(a);' +
          'a.addEventListener=(type,...args)=>{original(type,...args);' +
          'if(type==="click")queueMicrotask(()=>a.replaceWith(a.cloneNode(true)))};</script>');
      } else if (scenario==='trusted-no-retry' && path==='/p/session') {
        res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end(
          '<a id="initial" href="'+endpoint+'">Return</a><script>' +
          'const a=document.getElementById("initial"); a.addEventListener("click",event=>{' +
          'event.preventDefault(); fetch("/click-observed"); a.replaceWith(a.cloneNode(true));});</script>');
      } else if (scenario==='popup-return' && path==='/p/session') {
        res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end(
          '<title>Hosted flow</title><a rel="noopener" target="_blank" href="'+endpoint+'">Return</a>');
      } else if (scenario==='responsive-return' && path==='/p/session') {
        res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Content-Security-Policy':"script-src 'none'"}).end(
          '<title>Hosted flow</title><a hidden href="'+endpoint+'">Back</a>' +
          '<a href="'+endpoint.replace('/portal-return/','/original/../portal-return/')+'">Return</a>' +
          '<a href="'+endpoint+'">Back to merchant</a>');
      } else if (path==='/bootstrap.js') {
        res.writeHead(200,{'Content-Type':'application/javascript'}).end(
          'fetch("/write-never",{method:"POST"}).catch(()=>{})');
      } else if (['redirect','foreign-redirect'].includes(scenario) && path==='/p/session') {
        const location=scenario==='redirect'?'https://billing.stripe.com/p/demo':'https://foreign.invalid/p/demo';
        res.writeHead(302,{Location:location}).end();
      } else if (path==='/p/session' || path==='/p/demo') {
        res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end(
          '<!doctype html><title>Hosted account</title><script>fetch("/mutate",{method:"POST"}).catch(()=>{})</script>'+
          '<img src="https://foreign.invalid/pixel"><a href="'+endpoint+'">Back to merchant</a>');
      } else if (req.url===new URL(endpoint).pathname) {
        res.writeHead(200,{'Content-Type':'text/html'}).end('<title>Merchant account</title>');
      } else res.writeHead(404).end();
    });
    await new Promise((resolve,reject)=>server.once('error',reject).listen(0,'127.0.0.1',resolve));
    try {
      const local='http://127.0.0.1:'+server.address().port;
      endpoint=local+new URL(merchant).pathname;
      let observations;
      const journey=visit(sample,endpoint,(route,options)=>{
        const request=new URL(options.url);
        return route.fetch({...options,url:local+request.pathname+request.search});
      }, ['diagnose-hidden-return','popup-return','hydrated-return','trusted-no-retry'].includes(scenario)
        ? codes => { observations=codes; } : undefined, scenario!=='diagnose-hidden-return');
      if (scenario==='trusted-no-retry') {
        await assert.rejects(journey,{message:'RETURN_COMMIT'});
        assert.equal(observations.includes('TRUSTED_CLICK_SEEN'),true);
        assert.equal(requests.filter(r=>r.path==='/click-observed').length,1);
        assert.equal(requests.some(r=>r.path===new URL(endpoint).pathname),false);
        return;
      }
      if (scenario==='diagnose-hidden-return') {
        await assert.rejects(journey,{message:'RETURN_LINK'});
        assert.deepEqual(observations,['ANCHOR_TIMEOUT','DOM_ORIGINAL_DOCUMENT','FETCH_FAILED','PROVIDER_WRITE_BLOCKED','SCRIPT_OK']);
        assert.equal(requests.some(r=>r.method!=='GET' || r.path==='/dashboard'),false);
        return;
      }
      if (scenario==='foreign-redirect') {
        await assert.rejects(journey,{message:'HOSTED_DOCUMENT'});
        assert.deepEqual(requests.map(r=>r.path),['/p/session']);
        return;
      }
      await journey;
      if (scenario==='hydrated-return') {
        assert.equal(observations.includes('RETURN_NODE_DETACHED'),true);
        assert.equal(observations.includes('TRUSTED_CLICK_SEEN'),true);
      }
      if (scenario==='popup-return') assert.deepEqual(observations,[
        'CLICK_FINISHED','MERCHANT_GET_SEEN','MERCHANT_STATUS_OK','POPUP_CREATED','RECEIVER_URL_COMMITTED',
        'TARGET_POPUP','TRUSTED_CLICK_SEEN']);
      const back=requests.filter(r=>r.path===new URL(endpoint).pathname);
      assert.deepEqual(back,[{path:new URL(endpoint).pathname,method:'GET',referer:undefined}]);
      assert.equal(requests.some(r=>r.method!=='GET'||r.path==='/pixel'),false);
      assert.equal(requests.filter(r=>r.path==='/p/session').length,1);
      assert.equal(requests.filter(r=>r.path==='/p/demo').length,scenario==='redirect'?1:0);
    } finally {
      server.closeAllConnections();
      await new Promise(resolve=>server.close(resolve));
    }
  });
}
