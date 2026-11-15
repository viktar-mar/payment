import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import {fileURLToPath} from 'node:url';

const targetFile=fileURLToPath(new URL('../stripe-billing-failed-renewal.spec.ts',import.meta.url));
function isLoginHandoff(line:string):boolean {
  // Only the supported device URL and eight-letter pairing code can leave
  // this reporter. In particular the original opaque bearer fits neither.
  const prefix='Authorize the isolated Sandbox CLI at https://access.stripe.com/stripecli/oauth2/device using code ';
  const suffix='. Keep this terminal open.';
  return line.length===prefix.length+9+suffix.length && line.startsWith(prefix) && line.endsWith(suffix) &&
    /^[A-Z]{4}-[A-Z]{4}$/.test(line.slice(prefix.length,-suffix.length));
}

/** The process emits one bounded device-login handoff. All other output is opaque. */
export default class FailedRenewalReporter implements Reporter {
  private buffer='';
  private overflow=false;
  private handoff=false;
  private completed=0;
  private targetPassed=false;
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
    this.targetPassed=this.completed===1 && result.status==='passed' && result.retry===0 &&
      test.expectedStatus==='passed' && test.title==='OWNED_FAILED_RENEWAL_GRACE_RECOVERY' &&
      test.location.file===targetFile && test.parent.project()?.name==='owned-failed-renewal-manual-chrome';
    process.stdout.write(this.targetPassed?'owned_failed_renewal_passed\n':'owned_failed_renewal_not_accepted\n');
  }
  onEnd(result:FullResult):void {
    this.buffer='';this.handoff=true;
    const accepted=this.completed===1 && this.targetPassed && result.status==='passed';
    process.stdout.write(accepted?'owned_failed_renewal_result_passed full5b=NOT_ACCEPTED\n':'owned_failed_renewal_result_not_accepted full5b=NOT_ACCEPTED\n');
  }
}
