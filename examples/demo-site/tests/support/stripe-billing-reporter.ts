import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';
/** No browser errors, attachments, console messages or transport bodies enter native reports. */
export default class BillingReporter implements Reporter {
 private buffer='';private handoff=false;
 printsToStdio(){return true;}
 onStdOut(chunk:string|Buffer){
  if(this.handoff)return;
  for(const char of chunk.toString('utf8')){
   if(char==='\n'){
    const line=this.buffer;this.buffer='';
    const match=/^Authorize the isolated Sandbox CLI at (https:\/\/(?:access|dashboard)\.stripe\.com\/[^\s]*) using code ([A-Za-z0-9 -]{4,128})\. Keep this terminal open\.$/.exec(line);
    if(match&&!/(?:[spr]k_(?:test|live)_|whsec_|client_secret)/i.test(line)){this.handoff=true;process.stdout.write(line+'\n');}
   }else if(this.buffer.length<4096)this.buffer+=char;else this.buffer='';
  }
 }
 onStdErr(){}
 onError(){process.stdout.write('billing_setup_failed\n');}
 onTestEnd(_test:TestCase,result:TestResult){process.stdout.write(result.status==='passed'?'billing_initial_non_clock_passed\n':'billing_initial_non_clock_not_accepted\n');}
 onEnd(result:FullResult){this.buffer='';process.stdout.write(result.status==='passed'?'billing_result_passed full5b=NOT_ACCEPTED\n':'billing_result_not_accepted full5b=NOT_ACCEPTED\n');}
}
