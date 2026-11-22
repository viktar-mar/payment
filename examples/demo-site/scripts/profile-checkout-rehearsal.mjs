import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';

// Runs in the rehearsal document. Only the two attended actions use real browser input.
function rehearse() {
  const operation = {subscriptionId: 'local-subscription', operationId: 'local-payment'};
  const method = {...operation, operationId: 'local-method'};
  const deadline = Date.now() + 600000;
  const session = {customer: {id: 'local-customer'}, expiresAt: new Date(deadline).toISOString()};
  const paymentResult = {state: 'OBSERVE', retryAllowed: false, successorAttemptId: null};
  const methodResult = {...method, status: 'RESERVED', actionAvailable: false};
  const handle = result => ({confirm: async () => result, destroy() {}});
  const client = {
    requestEmailOtp: async () => ({challengeId:'local'}),
    verifyEmailOtp: async () => session,
    subscriptions: {action: async () => ({}), confirmationSession: async () => ({protocol:'SERVER_CONFIRM_V2'})},
  };
  window.__failedRenewalChallenge = async () => {};
  window.__failedRenewalPrepare = async () => operation;
  window.__failedRenewalTerminal = () => {};
  installRehearsalPage({
    createGenericPaymentClient: () => client,
    mountStripePaymentAttempt: async () => handle(paymentResult),
    mountStripeSubscriptionMethod: async () => handle(methodResult),
  });
  const get = id => document.getElementById(id);
  new MutationObserver(() => {
    switch (document.body.dataset.phase) {
      case 'CODE':
        get('code').value = '111111';
        get('verify').click();
        break;
      case 'AUTHENTICATED':
        get('consent').checked = true;
        get('start').click();
        break;
      case 'READY': get('pay').disabled = false; break;
      case 'OBSERVE': void window.__failedRenewalMethod(method); break;
      case 'METHOD_READY': get('confirm-method').disabled = false; break;
    }
  }).observe(document.body, {attributes:true, attributeFilter:['data-phase']});
  window.__failedRenewalConfigure({backend:'http://127.0.0.1:1', siteKey:'local', email:'fixture@example.invalid', deadline});
  get('request').click();
}

export async function buildRehearsal() {
  const [template, source] = await Promise.all([
    readFile(new URL('../billing-failed-renewal.html', import.meta.url), 'utf8'),
    readFile(new URL('../src/billing-failed-renewal-page.ts', import.meta.url), 'utf8'),
  ]);
  const js = ts.transpileModule(source, {compilerOptions: {
    module:ts.ModuleKind.CommonJS, target:ts.ScriptTarget.ES2022,
  }}).outputText;
  const scripts = [
    'const installRehearsalPage = (() => { const exports = {};\n' + js + '\nreturn exports.installFailedRenewalPage; })();',
    '(' + rehearse.toString() + ')();',
  ];
  const hashes = scripts.map(s => "'sha256-" + createHash('sha256').update(s).digest('base64') + "'");
  const policy = "default-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'; script-src " + hashes.join(' ');
  const entry = '<script type="module" src="/src/billing-failed-renewal.ts"></script>';
  if (!template.includes(entry) || scripts.some(s => /<\/script/i.test(s))) throw Error('Unexpected rehearsal source');
  return template.replace('<head>', '<head>\n<meta http-equiv="Content-Security-Policy" content="' + policy + '">')
    .replace(entry, '').replace(/<title>[^<]+<\/title>/, '<title>Subscription button rehearsal</title>')
    .replace(/<h1>[\s\S]*?<button id="request"/, '<h1>Subscription button rehearsal</h1><p>Local simulation. No charge. Click Pay, then Confirm test method.</p>\n<button id="request"')
    .replace('</body>', () => scripts.map(s => '<script>' + s + '</script>').join('\n') + '\n</body>');
}
