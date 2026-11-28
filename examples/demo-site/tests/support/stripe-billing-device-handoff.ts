function isLoginHandoff(line:string):boolean {
  // Only the supported device URL and eight-letter pairing code can leave
  // this reporter. In particular the original opaque bearer fits neither.
  const prefix='Authorize the isolated Sandbox CLI at https://access.stripe.com/stripecli/oauth2/device using code ';
  const suffix='. Keep this terminal open.';
  return line.length===prefix.length+9+suffix.length && line.startsWith(prefix) && line.endsWith(suffix) &&
    /^[A-Z]{4}-[A-Z]{4}$/.test(line.slice(prefix.length,-suffix.length));
}

/** Only the isolated CLI device URL and pairing code can leave a worker. */
export class BillingDeviceHandoff {
  private buffer='';
  private overflow=false;
  private handoff=false;
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
  end():void {this.buffer='';this.handoff=true;}
}
