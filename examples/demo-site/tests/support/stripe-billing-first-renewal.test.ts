import test from 'node:test';
import assert from 'node:assert/strict';
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
