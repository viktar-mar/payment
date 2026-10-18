import {test,chromium} from '@playwright/test';
import {runFormLoadDiagnostic} from '../../../infrastructure/stripe-form-diagnostic.mjs';
import {formatFormDiagnosticFrame} from '../../../infrastructure/stripe-form-diagnostic-schema.mjs';
import {runFormLoadBrowser} from './support/form-load-diagnostic';

// Intentionally no Playwright browser fixture: the driver owns and proves the
// closure of its sole launch/context. Importing/collecting this test is inert.
test('one prepare-only form load; never confirms a payment',async()=>{
  const env=process.env,deadline=Date.now()+900000,controller=new AbortController();
  const ports={backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
  for(const name of Object.keys(ports) as (keyof typeof ports)[]){
    const value=env['PAYMENT_STRIPE_SANDBOX_PORT_'+name.toUpperCase()];
    ports[name]=typeof value==='string'&&/^\d{4,5}$/.test(value)?Number(value):0;
  }
  const result=await runFormLoadDiagnostic({
    env:{PAYMENT_STRIPE_SANDBOX_ENABLED:env.PAYMENT_STRIPE_SANDBOX_ENABLED,
      PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:env.PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC,
      PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:env.PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY,
      PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:env.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS,
      PAYMENT_STRIPE_SANDBOX_OWNED_RUN:env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN,
      PAYMENT_STRIPE_SANDBOX_FULL_RUN:env.PAYMENT_STRIPE_SANDBOX_FULL_RUN,
      DEBUG:env.DEBUG,NODE_DEBUG:env.NODE_DEBUG,PWDEBUG:env.PWDEBUG,PW_TRACE:env.PW_TRACE},
    runId:env.PAYMENT_STRIPE_SANDBOX_RUN_ID??'',expectedSource:env.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE??'',
    daemon:env.PAYMENT_STRIPE_SANDBOX_DAEMON??'',ports,
  },{deadline,signal:controller.signal},{runBrowser:(handoff,limits)=>runFormLoadBrowser(chromium,handoff,limits)});
  for(const frame of result.frames){const wire=formatFormDiagnosticFrame(frame);if(wire)process.stdout.write(wire);}
  if(result.exitCode!==0)throw new Error('form_diagnostic_failed_manual_check');
});
