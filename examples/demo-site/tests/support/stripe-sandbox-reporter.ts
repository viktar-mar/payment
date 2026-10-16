import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import {formatSandboxAuthorization} from '../../../../infrastructure/stripe-sandbox-acceptance.mjs';

/** Never delegate errors/steps/stdout/attachments to a detailed reporter: iframe URLs can contain capabilities. */
export default class StripeSandboxReporter implements Reporter {
  private cases=0;
  private failures=0;
  private readonly owned=process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN==='true';
  private buffer='';
  private handoff=false;
  private handoffFailed=false;
  printsToStdio(){return true;}
  private rejectHandoff(){if(!this.handoffFailed){this.handoffFailed=true;this.failures++;process.stdout.write('sandbox_authorization_failed_manual_check\n');}this.buffer='';}
  onStdOut(chunk:string|Buffer){
    if(!this.owned||this.handoffFailed)return;
    // At most one bounded line is retained, including split transport chunks.
    for(const char of chunk.toString('utf8')) {
      if(char==='\n'){
        const line=this.buffer;this.buffer='';
        if(!line.includes('stripe_sandbox_authorization'))continue;
        try{if(this.handoff)throw null;const formatted=formatSandboxAuthorization(JSON.parse(line));if(formatted===null)throw null;
          this.handoff=true;process.stdout.write(formatted);
        }catch{this.rejectHandoff();return;}
      }else {this.buffer+=char;if(Buffer.byteLength(this.buffer)>4096){this.rejectHandoff();return;}}
    }
  }
  onStdErr(_chunk?:string|Buffer){}
  onError(){this.failures++;process.stdout.write('sandbox_setup_failed_manual_check\n');}
  onTestEnd(_test:TestCase,result:TestResult){
    this.cases++;
    const status=['passed','failed','timedOut','skipped','interrupted'].includes(result.status)?result.status:'failed';
    if(status!=='passed'&&status!=='skipped')this.failures++;
    process.stdout.write(`sandbox_case_${this.cases}_${status}\n`);
    if(status!=='passed')process.stdout.write('sandbox_pending_or_manual_check\n');
  }
  async onEnd(result:FullResult){
    if(this.owned&&this.buffer.includes('stripe_sandbox_authorization'))this.rejectHandoff();
    this.buffer='';
    const status=this.handoffFailed?'failed':['passed','failed','timedout','interrupted'].includes(result.status)?result.status:'failed';
    process.stdout.write(`sandbox_result_${status} cases=${this.cases} failures=${this.failures}\n`);
    if(this.handoffFailed)return {status:'failed' as const};
  }
}
