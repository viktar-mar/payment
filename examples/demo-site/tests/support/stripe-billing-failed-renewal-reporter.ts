// @ts-ignore Explicit source for Node's TypeScript test runner.
import {BillingDeviceHandoff} from './stripe-billing-device-handoff.ts';
import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import {fileURLToPath} from 'node:url';
import {parseBillingFailedRenewalDiagnosticLine} from '../../../../infrastructure/stripe-billing-first-payment.mjs';

const targetFile=fileURLToPath(new URL('../stripe-billing-failed-renewal.spec.ts',import.meta.url));
function isTarget(test:TestCase,result:TestResult):boolean {
  try{
    return result.retry===0 && test.expectedStatus==='passed' && test.title==='OWNED_FAILED_RENEWAL_GRACE_RECOVERY' &&
      test.location.file===targetFile && test.parent.project()?.name==='owned-failed-renewal-manual-chrome';
  }catch{return false;}
}
function cleanTerminalBaseline(value:TestCase|TestResult):boolean {
  try{
    const annotations=Object.getOwnPropertyDescriptor(value,'annotations')?.value;
    if(!Array.isArray(annotations))return false;
    for(let index=0;index<annotations.length;index++){
      const annotation=Object.getOwnPropertyDescriptor(annotations,String(index))?.value;
      if(!annotation)return false;
      const type=Object.getOwnPropertyDescriptor(annotation,'type');
      if(!type||!Object.hasOwn(type,'value')||type.value==='owned-failed-renewal-terminal')return false;
    }
    return true;
  }catch{return false;}
}

function terminalDiagnostic(result:TestResult):string|null {
  // A matching clean begin must already establish this result's provenance.
  // Do not inspect errors, attachments, stdout or end-case aggregate annotations.
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
  private readonly device=new BillingDeviceHandoff();
  private completed=0;
  private targetPassed=false;
  private terminalEmitted=false;
  private begun=0;
  private terminalBegin:{test:TestCase;result:TestResult}|null=null;
  printsToStdio():boolean{return true;}
  onStdOut(chunk:string|Buffer):void {this.device.onStdOut(chunk);}
  onStdErr():void {}
  onError():void{process.stdout.write('failed_renewal_setup_failed\n');}
  onTestBegin(test:TestCase,result:TestResult):void {
    this.begun++;this.terminalBegin=null;
    if(this.begun===1 && this.completed===0 && isTarget(test,result) && cleanTerminalBaseline(test) && cleanTerminalBaseline(result)){
      this.terminalBegin={test,result};
    }
  }
  onTestEnd(test:TestCase,result:TestResult):void {
    const trustedBegin=this.begun===1 && this.terminalBegin?.test===test && this.terminalBegin.result===result;
    this.terminalBegin=null;
    this.completed++;
    const target=this.completed===1 && isTarget(test,result);
    this.targetPassed=target && result.status==='passed';
    if(target && trustedBegin && !this.terminalEmitted && ['failed','timedOut','interrupted'].includes(result.status)){
      const diagnostic=terminalDiagnostic(result);
      if(diagnostic){this.terminalEmitted=true;process.stdout.write(diagnostic+'\n');}
    }
    process.stdout.write(this.targetPassed?'owned_failed_renewal_passed\n':'owned_failed_renewal_not_accepted\n');
  }
  onEnd(result:FullResult):void {
    this.device.end();
    const accepted=this.completed===1 && this.targetPassed && result.status==='passed';
    process.stdout.write(accepted?'owned_failed_renewal_result_passed full5b=NOT_ACCEPTED\n':'owned_failed_renewal_result_not_accepted full5b=NOT_ACCEPTED\n');
  }
}
