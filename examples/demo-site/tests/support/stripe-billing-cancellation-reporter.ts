import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import {fileURLToPath} from 'node:url';
// @ts-ignore Explicit source for Node's TypeScript test runner.
import {BillingDeviceHandoff} from './stripe-billing-device-handoff.ts';

const target=fileURLToPath(new URL('../stripe-billing-cancellation.spec.ts',import.meta.url));
export default class CancellationReporter implements Reporter {
  private readonly device=new BillingDeviceHandoff();
  private started=0;
  private finished=0;
  private passed=false;
  private errored=false;
  private current:TestCase|undefined;
  printsToStdio(){return true;}
  onStdOut(chunk:string|Buffer){this.device.onStdOut(chunk);}
  onStdErr(){}
  onError(){this.errored=true;this.current=undefined;process.stdout.write('billing_cancellation_setup_failed\n');}
  onTestBegin(test:TestCase,result:TestResult){
    this.started++;
    if(this.started===1 && result.retry===0 && test.expectedStatus==='passed' && test.title==='OWNED_PERIOD_END_CANCELLATION' &&
       test.location.file===target && test.parent.project()?.name==='owned-period-end-cancellation')this.current=test;
  }
  onTestEnd(test:TestCase,result:TestResult){
    this.finished++;
    this.passed=this.current===test && result.retry===0 && result.status==='passed';
    this.current=undefined;
  }
  onEnd(result:FullResult){
    this.device.end();
    const passed=!this.errored && this.started===1 && this.finished===1 && this.passed && result.status==='passed';
    process.stdout.write(passed?'owned_period_end_cancellation_passed full5b=NOT_ACCEPTED\n':'owned_period_end_cancellation_not_accepted full5b=NOT_ACCEPTED\n');
  }
}
