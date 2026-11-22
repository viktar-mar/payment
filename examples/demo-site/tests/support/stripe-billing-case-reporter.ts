import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import {fileURLToPath} from 'node:url';
// @ts-ignore Explicit source for Node's TypeScript test runner.
import {BillingDeviceHandoff} from './stripe-billing-device-handoff.ts';

interface ReportCase {spec:URL; title:string; project:string; marker:string; setupFailure:string;terminal?:{type:string;parse:(raw:unknown)=>string|null}}
function terminalValues(source:TestCase|TestResult,type:string):unknown[]|undefined{
 try{
  const values:unknown[]=[],annotations=Object.getOwnPropertyDescriptor(source,'annotations')?.value;
  if(!Array.isArray(annotations))return;
  for(let i=0;i<annotations.length;i++){
   const item=Object.getOwnPropertyDescriptor(annotations,i)?.value,kind=item&&Object.getOwnPropertyDescriptor(item,'type')?.value;
   if(typeof kind!=='string')return;
   if(kind===type)values.push(Object.getOwnPropertyDescriptor(item,'description')?.value);
  }
  return values;
 }catch{return;}
}
export function fixedBillingReporter(selected:ReportCase) {
 const target=fileURLToPath(selected.spec);
 return class FixedBillingReporter implements Reporter {
  private readonly device=new BillingDeviceHandoff();
  private started=0;
  private finished=0;
  private passed=false;
  private errored=false;
  private current:{test:TestCase;result:TestResult;clean:boolean}|undefined;
  private diagnostic:string|null=null;
  printsToStdio(){return true;}
  onStdOut(chunk:string|Buffer){this.device.onStdOut(chunk);}
  onStdErr(){}
  onError(){this.errored=true;this.current=undefined;process.stdout.write(selected.setupFailure+'\n');}
  onTestBegin(test:TestCase,result:TestResult){
    this.started++;
    if(this.started===1 && result.retry===0 && test.expectedStatus==='passed' && test.title===selected.title &&
       test.location.file===target && test.parent.project()?.name===selected.project)this.current={test,result,clean:!selected.terminal ||
         terminalValues(test,selected.terminal.type)?.length===0 && terminalValues(result,selected.terminal.type)?.length===0};
  }
  onTestEnd(test:TestCase,result:TestResult){
    this.finished++;
    const original=this.current?.test===test && this.current.result===result && result.retry===0;
    this.passed=original && result.status==='passed';
    if(original && this.current?.clean && selected.terminal && ['failed','timedOut','interrupted'].includes(result.status)){
      const lines=terminalValues(result,selected.terminal.type);
      if(lines?.length===1)this.diagnostic=selected.terminal.parse(lines[0]);
    }
    this.current=undefined;
  }
  onEnd(result:FullResult){
    this.device.end();
    const passed=!this.errored && this.started===1 && this.finished===1 && this.passed && result.status==='passed';
    if(this.diagnostic && !this.errored && this.started===1 && this.finished===1 && result.status!=='passed')process.stdout.write(this.diagnostic+'\n');
    process.stdout.write(selected.marker+(passed?'_passed':'_not_accepted')+' full5b=NOT_ACCEPTED\n');
  }
 };
}
