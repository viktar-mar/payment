import test from 'node:test';
import assert from 'node:assert/strict';
test('combined reporter independently filters finite cleanup and primary lines with bounded chunks and floods',async()=>{
 const {default:Reporter}=await import(new URL('./stripe-billing-first-renewal-reporter.ts',import.meta.url).href);
 const primary='owned_initial_and_first_renewal_diagnostic code=PREPARE_REQUEST_RESPONSE cleanup=UNCONFIRMED';
 const cleanup='owned_initial_and_first_renewal_cleanup_diagnostic code=LISTENER_STOP';
 const handoff='Authorize the isolated Sandbox CLI at https://access.stripe.com/fixture using code ABCD EFGH. Keep this terminal open.';
 const original=process.stdout.write,output:string[]=[];process.stdout.write=((v:unknown)=>{output.push(String(v));return true;}) as typeof process.stdout.write;
 try {
  for(const cleanupFirst of [false,true]){
   const reporter=new Reporter(),start=output.length;
   for(const bad of ['secret-sentinel',cleanup+' extra=secret-sentinel',cleanup.replace('LISTENER_STOP','UNKNOWN'),cleanup.replace('LISTENER_STOP','LISTENER_STOP_ALIAS'),cleanup.replace('LISTENER_STOP','LISTENER_STOP1'),cleanup.replace('LISTENER_STOP','sk_test_SENTINEL'),cleanup.replace('owned_initial_and_first_renewal','owned_initial_payment'),primary.replace('owned_initial_and_first_renewal','owned_initial_payment')])reporter.onStdOut(bad+'\n');
   reporter.onStdOut('x'.repeat(4096));reporter.onStdOut(cleanup+'\n');
   reporter.onStdErr(cleanup+'\nprivate-sentinel\n');
   const ordered=cleanupFirst?[cleanup,primary]:[primary,cleanup];
   for(const line of ordered)for(const character of line+'\n')reporter.onStdOut(Buffer.from(character));
   for(let i=0;i<100;i++)reporter.onStdOut(primary+'\n'+cleanup+'\n'+handoff+'\n');
   reporter.onStdOut(cleanup+'\r\n');reporter.onStdOut('private-sentinel');reporter.onEnd({status:'failed'});
   reporter.onError(new Proxy({},{get(){assert.fail('raw error must stay opaque');}}));
   assert.deepEqual(output.slice(start),[...ordered.map(line=>line+'\n'),handoff+'\n','owned_initial_and_first_renewal_result_not_accepted full5b=NOT_ACCEPTED\n','first_renewal_setup_failed\n']);
  }
  for(const code of ['BROWSER_UNSETTLED','BROWSER_CLOSE','RUNTIME_CLOSE','LISTENER_STOP','START_SETTLEMENT','BOOTSTRAP_STOP','FIXTURE_CLOSE']){
   const reporter=new Reporter(),start=output.length,line='owned_initial_and_first_renewal_cleanup_diagnostic code='+code;
   reporter.onStdOut(line+'\n'+line+'\n');assert.deepEqual(output.slice(start),[line+'\n']);
  }
 }finally{process.stdout.write=original;}
});
// No browser/runtime invocation: inert reporter and owner/diagnostic modules only.
test('combined reporter keeps closed scope labels and inherited filtered output',async()=>{
 const {default:Reporter}=await import(new URL('./stripe-billing-first-renewal-reporter.ts',import.meta.url).href);
 const original=process.stdout.write,output:string[]=[];
 process.stdout.write=((value:unknown)=>{output.push(String(value));return true;}) as typeof process.stdout.write;
 try {
  const reporter=new Reporter();reporter.onStdErr('secret-sentinel');reporter.onStdOut('secret-sentinel\n');reporter.onStdOut('owned_initial_payment_diagnostic code=BROWSER_PAGE cleanup=CONFIRMED\n');
  reporter.onStdOut('owned_initial_and_first_renewal_diagnostic code=BROWSER_PAGE cleanup=CONFIRMED\n');
  reporter.onError({get message(){throw Error('must not read');}});reporter.onTestEnd({title:'secret-sentinel'}, {status:'failed',errors:['secret-sentinel']});reporter.onEnd({status:'failed'});
  assert.deepEqual(output,['owned_initial_and_first_renewal_diagnostic code=BROWSER_PAGE cleanup=CONFIRMED\n','first_renewal_setup_failed\n','owned_initial_and_first_renewal_not_accepted\n','owned_initial_and_first_renewal_result_not_accepted full5b=NOT_ACCEPTED\n']);
 }finally {process.stdout.write=original;}
});
for(const stage of ['SUBMIT','TOKEN'])test('combined reporter preserves LAST_OBSERVED '+stage+' under envelope limit',async()=>{
 const {default:Reporter}=await import(new URL('./stripe-billing-first-renewal-reporter.ts',import.meta.url).href);
 const {parseBillingFirstRenewalDiagnosticLine}=await import('../../../../infrastructure/stripe-billing-first-payment.mjs');
 const line='owned_initial_and_first_renewal_diagnostic code=BROWSER_WAIT_OBSERVE_LAST_OBSERVED_'+stage+' cleanup=UNCONFIRMED';
 assert.ok(line.length<160);assert.equal(parseBillingFirstRenewalDiagnosticLine(line),line);
 const original=process.stdout.write,output:string[]=[];process.stdout.write=((value:unknown)=>{output.push(String(value));return true;}) as typeof process.stdout.write;
 try{new Reporter().onStdOut(line+'\n');assert.deepEqual(output,[line+'\n']);}finally{process.stdout.write=original;}
});
for(const code of ['BROWSER_MANUAL_PAY_ARM','BROWSER_MANUAL_PAY_WAIT'])test('combined reporter preserves bounded '+code,async()=>{
 const {default:Reporter}=await import(new URL('./stripe-billing-first-renewal-reporter.ts',import.meta.url).href);
 const {parseBillingFirstRenewalDiagnosticLine}=await import('../../../../infrastructure/stripe-billing-first-payment.mjs');
 const line='owned_initial_and_first_renewal_diagnostic code='+code+' cleanup=CONFIRMED';
 assert.ok(line.length<160);assert.equal(parseBillingFirstRenewalDiagnosticLine(line),line);
 const original=process.stdout.write,output:string[]=[];process.stdout.write=((value:unknown)=>{output.push(String(value));return true;}) as typeof process.stdout.write;
 try{new Reporter().onStdOut(line+'\n');assert.deepEqual(output,[line+'\n']);}finally{process.stdout.write=original;}
});
