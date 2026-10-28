import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import {formatFormDiagnosticFrame,parseFormDiagnosticFrame} from '../../../../infrastructure/stripe-form-diagnostic-schema.mjs';
import type {FormDiagnosticFrame} from '../../../../infrastructure/stripe-form-diagnostic-schema.mjs';

const READY = new Set(['FORM_READY_BEFORE_15S','FORM_READY_AFTER_15S']);
const PENDING = new Set(['WAITING_CLIENT_SESSION','WAITING_STRIPE_LOADER','ELEMENT_SETUP_INCOMPLETE',
  'WAITING_ELEMENT_READY','WAITING_PRESENTATION_CATCHUP','WIDGET_HANDOFF_INCOMPLETE']);

/** The only output is validated finite frames and one fixed failure marker. */
export default class FormLoadDiagnosticReporter implements Reporter {
  private buffer='';
  private bytes=0;
  private invalid=false;
  private cases=0;
  private passed=false;
  private readonly frames:string[]=[];
  private final:FormDiagnosticFrame|null=null;

  printsToStdio(){return true;}

  private reject(){
    if(!this.invalid){this.invalid=true;process.stdout.write('form_diagnostic_failed_manual_check\n');}
    this.buffer='';this.bytes=0;
  }

  onStdOut(chunk:string|Buffer){
    if(this.invalid)return;
    for(const char of typeof chunk==='string'?chunk:chunk.toString('utf8')){
      if(char==='\n'){
        const line=this.buffer;this.buffer='';this.bytes=0;
        // Ordinary process output is suppressed. A JSON-looking line is always
        // treated as a candidate frame, so malformed frames cannot disappear.
        if(!line.trimStart().startsWith('{'))continue;
        const frame=parseFormDiagnosticFrame(line+'\n');
        const formatted=frame?formatFormDiagnosticFrame(frame):null;
        if(!frame||!formatted||this.frames.length>=2||this.frames.includes(formatted)){
          this.reject();return;
        }
        // A primary failure or an already-final ready frame cannot be erased
        // by another frame. A first pending checkpoint carries no such failure.
        if(this.final&&!PENDING.has(this.final.result)){this.reject();return;}
        this.frames.push(formatted);this.final=frame;process.stdout.write(formatted);
      }else{
        this.bytes+=Buffer.byteLength(char,'utf8');
        if(this.bytes>=4096){this.reject();return;}
        this.buffer+=char;
      }
    }
  }

  onStdErr(_chunk?:string|Buffer){}
  onError(){this.reject();}

  onTestEnd(_test:TestCase,result:TestResult){
    this.cases++;
    if(result.status!=='passed'||this.cases!==1)this.reject();
    else this.passed=true;
  }

  async onEnd(result:FullResult):Promise<{status:'passed'|'failed'}>{
    if(this.buffer.length)this.reject();
    const final=this.final;
    if(result.status!=='passed'||this.cases!==1||!this.passed||!final||!READY.has(final.result)
      ||!final.no_confirmation||!final.browser_closed||!final.context_closed||!final.runtime_closed)this.reject();
    this.buffer='';this.bytes=0;
    return {status:this.invalid?'failed':'passed'};
  }
}
