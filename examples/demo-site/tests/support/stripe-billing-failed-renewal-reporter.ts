import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import {fileURLToPath} from 'node:url';
import {parseBillingFailedRenewalDiagnosticLine} from '../../../../infrastructure/stripe-billing-first-payment.mjs';

const targetFile=fileURLToPath(new URL('../stripe-billing-failed-renewal.spec.ts',import.meta.url));
function isLoginHandoff(line:string):boolean {
  // Only the supported device URL and eight-letter pairing code can leave
  // this reporter. In particular the original opaque bearer fits neither.
  const prefix='Authorize the isolated Sandbox CLI at https://access.stripe.com/stripecli/oauth2/device using code ';
  const suffix='. Keep this terminal open.';
  return line.length===prefix.length+9+suffix.length && line.startsWith(prefix) && line.endsWith(suffix) &&
    /^[A-Z]{4}-[A-Z]{4}$/.test(line.slice(prefix.length,-suffix.length));
}

function terminalDiagnostic(result:TestResult):string|null {
  // Only the post-close annotation in this invocation's result is a terminal channel.
  // Do not inspect errors, attachments, stdout or the case's aggregate annotations.
  try{
    const annotations=Object.getOwnPropertyDescriptor(result,'annotations')?.value;
    if(!Array.isArray(annotations))return null;
    let diagnostic:string|null=null,count=0;
    for(let index=0;index<annotations.length;index++){
      const annotation=Object.getOwnPropertyDescriptor(annotations,String(index))?.value;
      if(!annotation||Object.getOwnPropertyDescriptor(annotation,'type')?.value!=='owned-failed-renewal-terminal')continue;
      count++;
      diagnostic=parseBillingFailedRenewalDiagnosticLine(Object.getOwnPropertyDescriptor(annotation,'description')?.value);
    }
    return count===1?diagnostic:null;
  }catch{return null;}
}

/** Only the bounded device handoff and trusted finite terminal annotation may leave. */
export default class FailedRenewalReporter implements Reporter {
  private buffer='';
  private overflow=false;
  private handoff=false;
  private completed=0;
  private targetPassed=false;
  private terminalEmitted=false;
  printsToStdio():boolean{return true;}
  onStdOut(chunk:string|Buffer):void {
    if(this.handoff)return;
    for(const character of chunk.toString('utf8')){
      if(character==='\n'){
        const line=this.overflow?'':this.buffer;
        this.buffer='';this.overflow=false;
        if(isLoginHandoff(line)){
          this.handoff=true;process.stdout.write(line+'\n');return;
        }
      }else if(!this.overflow && this.buffer.length<4096){
        this.buffer+=character;
      }else{
        this.buffer='';this.overflow=true;
      }
    }
  }
  onStdErr():void {}
  onError():void{process.stdout.write('failed_renewal_setup_failed\n');}
  onTestEnd(test:TestCase,result:TestResult):void {
    this.completed++;
    const target=this.completed===1 && result.retry===0 &&
      test.expectedStatus==='passed' && test.title==='OWNED_FAILED_RENEWAL_GRACE_RECOVERY' &&
      test.location.file===targetFile && test.parent.project()?.name==='owned-failed-renewal-manual-chrome';
    this.targetPassed=target && result.status==='passed';
    if(target && !this.terminalEmitted && ['failed','timedOut','interrupted'].includes(result.status)){
      const diagnostic=terminalDiagnostic(result);
      if(diagnostic){this.terminalEmitted=true;process.stdout.write(diagnostic+'\n');}
    }
    process.stdout.write(this.targetPassed?'owned_failed_renewal_passed\n':'owned_failed_renewal_not_accepted\n');
  }
  onEnd(result:FullResult):void {
    this.buffer='';this.handoff=true;
    const accepted=this.completed===1 && this.targetPassed && result.status==='passed';
    process.stdout.write(accepted?'owned_failed_renewal_result_passed full5b=NOT_ACCEPTED\n':'owned_failed_renewal_result_not_accepted full5b=NOT_ACCEPTED\n');
  }
}
