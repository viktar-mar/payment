import type {Reporter,TestCase,TestResult,FullResult} from '@playwright/test/reporter';

function isLoginHandoff(line:string):boolean {
  const match=/^Authorize the isolated Sandbox CLI at (https:\/\/(?:access|dashboard)\.stripe\.com\/[^\s]*) using code ([A-Za-z0-9][A-Za-z0-9 -]{2,126}[A-Za-z0-9])\. Keep this terminal open\.$/.exec(line);
  if(!match)return false;
  try{
    const url=new URL(match[1]!);
    const decoded=decodeURIComponent(line);
    return url.href===match[1] && !url.hash && !/[\\\x00-\x1f\x7f]|%[0-9a-f]{2}/i.test(decoded) &&
      !/(?:[spr]k_(?:test|live)_|whsec_|bga1_|bca2_|ctoken_|_secret_|client[_ -]?secret|session[_ -]?token|bearer[ +])/i.test(decoded);
  }catch{return false;}
}

/** The process emits one bounded device-login handoff. All other output is opaque. */
export default class FailedRenewalReporter implements Reporter {
  private buffer='';
  private overflow=false;
  private handoff=false;
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
  onTestEnd(_test:TestCase,result:TestResult):void {
    process.stdout.write(result.status==='passed'?'owned_failed_renewal_passed\n':'owned_failed_renewal_not_accepted\n');
  }
  onEnd(result:FullResult):void {
    this.buffer='';this.handoff=true;
    process.stdout.write(result.status==='passed'?'owned_failed_renewal_result_passed full5b=NOT_ACCEPTED\n':'owned_failed_renewal_result_not_accepted full5b=NOT_ACCEPTED\n');
  }
}
