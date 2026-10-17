import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import {formatSandboxAuthorization,formatSandboxDiagnostic} from '../../../../infrastructure/stripe-sandbox-acceptance.mjs';

/** Never delegate errors/steps/stdout/attachments to a detailed reporter: iframe URLs can contain capabilities. */
export default class StripeSandboxReporter implements Reporter {
  private cases=0;
  private failures=0;
  private readonly owned=process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN==='true';
  private buffer='';
  private handoff=false;
  private handoffFailed=false;
  private diagnostic=false;
  private diagnosticFailed=false;
  printsToStdio(){return true;}
  private rejectHandoff(){if(!this.handoffFailed){this.handoffFailed=true;this.failures++;process.stdout.write('sandbox_authorization_failed_manual_check\n');}this.buffer='';}
  private rejectDiagnostic(){if(!this.diagnosticFailed){this.diagnosticFailed=true;this.failures++;process.stdout.write('sandbox_diagnostic_failed_manual_check\n');}this.buffer='';}
  private rejectFrame(line:string){
    if(line.includes('stripe_sandbox_diagnostic')||!line.includes('stripe_sandbox_authorization'))this.rejectDiagnostic();else this.rejectHandoff();
  }
  onStdOut(chunk:string|Buffer){
    if(!this.owned||this.handoffFailed||this.diagnosticFailed)return;
    // At most one bounded line is retained, including split transport chunks.
    for(const char of chunk.toString('utf8')) {
      if(char==='\n'){
        const line=this.buffer;this.buffer='';
        let envelope:unknown;
        try{envelope=JSON.parse(line);}catch{
          if(line.includes('stripe_sandbox_authorization')||line.includes('stripe_sandbox_diagnostic')||line.trimStart().startsWith('{')){this.rejectFrame(line);return;}
          continue;
        }
        // JSON.parse creates plain data: no dependency errors or arbitrary objects are inspected.
        const tag=typeof envelope==='object'&&envelope!==null?(envelope as {tag?:unknown}).tag:undefined;
        const authorization=tag==='stripe_sandbox_authorization';
        const diagnosticShape=typeof envelope==='object'&&envelope!==null&&['phase','code','exitCode','cleanupConfirmed'].some(key=>Object.hasOwn(envelope,key));
        if(diagnosticShape||(!authorization&&(typeof tag==='string'&&tag.startsWith('stripe_sandbox_diagnostic')||line.includes('stripe_sandbox_diagnostic')))){
          try{if(this.diagnostic)throw null;const formatted=formatSandboxDiagnostic(envelope);if(formatted===null)throw null;
            // Validated envelopes contain only flat primitives without colons. A
            // separator per decoded key also rejects escaped duplicate JSON names.
            if(line.match(/:/g)?.length!==Object.keys(envelope as object).length)throw null;
            this.diagnostic=true;this.failures++;process.stdout.write(formatted);
          }catch{this.rejectDiagnostic();return;}
          continue;
        }
        if(!authorization&&!line.includes('stripe_sandbox_authorization'))continue;
        try{if(this.handoff)throw null;const formatted=formatSandboxAuthorization(envelope);if(formatted===null)throw null;
          this.handoff=true;process.stdout.write(formatted);
        }catch{this.rejectHandoff();return;}
      }else {this.buffer+=char;if(Buffer.byteLength(this.buffer)>4096){this.rejectFrame(this.buffer);return;}}
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
    if(this.owned&&this.buffer)this.rejectFrame(this.buffer);
    this.buffer='';
    const diagnosticFailure=this.diagnostic||this.diagnosticFailed;
    const status=this.handoffFailed||diagnosticFailure?'failed':['passed','failed','timedout','interrupted'].includes(result.status)?result.status:'failed';
    process.stdout.write(`sandbox_result_${status} cases=${this.cases} failures=${this.failures}\n`);
    if(this.handoffFailed||diagnosticFailure)return {status:'failed' as const};
  }
}
