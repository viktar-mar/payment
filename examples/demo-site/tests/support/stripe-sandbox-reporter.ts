import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';

/** Never delegate errors/steps/stdout/attachments to a detailed reporter: iframe URLs can contain capabilities. */
export default class StripeSandboxReporter implements Reporter {
  private cases=0;
  private failures=0;
  printsToStdio(){return true;}
  onStdOut(){}
  onStdErr(){}
  onError(){this.failures++;process.stdout.write('sandbox_setup_failed_manual_check\n');}
  onTestEnd(_test:TestCase,result:TestResult){
    this.cases++;
    const status=['passed','failed','timedOut','skipped','interrupted'].includes(result.status)?result.status:'failed';
    if(status!=='passed'&&status!=='skipped')this.failures++;
    process.stdout.write(`sandbox_case_${this.cases}_${status}\n`);
    if(status!=='passed')process.stdout.write('sandbox_pending_or_manual_check\n');
  }
  onEnd(result:FullResult){
    const status=['passed','failed','timedout','interrupted'].includes(result.status)?result.status:'failed';
    process.stdout.write(`sandbox_result_${status} cases=${this.cases} failures=${this.failures}\n`);
  }
}
