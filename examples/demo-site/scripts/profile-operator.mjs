import {createHash,randomBytes} from 'node:crypto';
import {createServer} from 'node:http';
import {mkdir,writeFile} from 'node:fs/promises';
import {dirname} from 'node:path';

function operateTab() {
  let token=location.hash.slice(1), finished=false, busy=false, generation=0, timer;
  history.replaceState(null,'',location.pathname);
  const status=document.getElementById('status');
  const buttons=[...document.querySelectorAll('button')];
  const lock=()=>buttons.forEach(button=>{button.disabled=true;});
  const stop=()=>{finished=true;generation++;clearTimeout(timer);token='';lock();status.textContent='Session closed';};
  window.addEventListener('pagehide',()=>{
    if(!finished&&token)void fetch('/leave',{method:'POST',credentials:'omit',redirect:'error',
      keepalive:true,headers:{'X-Operator-Session':token}}).catch(()=>{});
    stop();
  },{once:true});
  async function post(path) {
    const reply=await fetch('/'+path,{method:'POST',credentials:'omit',cache:'no-store',redirect:'error',
      headers:{'X-Operator-Session':token}});
    if(!reply.ok)throw Error();
    return reply.text();
  }
  async function refresh() {
    if(finished||busy)return;
    const current=generation;
    try {
      const phase=await post('state');
      if(finished||busy||current!==generation)return;
      lock();
      if(phase==='initial'||phase==='method'){
        document.getElementById(phase).disabled=false;
        status.textContent=phase==='initial'?'Ready for Pay':'Ready to confirm the replacement method';
      }else if(phase==='done'){
        finished=true;token='';status.textContent='Both clicks received. The runner verifies the result.';return;
      }else if(phase==='waiting')status.textContent='Waiting for the test form';
      else throw Error();
      timer=setTimeout(refresh,500);
    }catch{if(current===generation)stop();}
  }
  for(const button of buttons)button.addEventListener('click',async event=>{
    if(!event.isTrusted||button.disabled||busy||finished||document.visibilityState!=='visible')return;
    busy=true;generation++;clearTimeout(timer);lock();
    try{await post(button.id);busy=false;void refresh();}catch{stop();}
  });
  post('claim').then(value=>{if(!finished){token=value;void refresh();}},stop);
}

const script='('+operateTab.toString()+')();';
const hash=createHash('sha256').update(script).digest('base64');
const denied=()=>new Error('Profile checkout closed');

export async function openProfileOperator({rehearsal,onAbandon}) {
  if(typeof rehearsal!=='boolean')throw denied();
  const title=rehearsal?'Checkout rehearsal':'Stripe Sandbox recovery';
  const description=rehearsal?'Local simulation. No charge.':'Test subscription: initial payment €20, then recovery of the original €20 debt.';
  const html='<!doctype html><html lang="en"><meta charset="utf-8"><title>'+title+'</title>'+
    '<main><h1>'+title+'</h1><p>'+description+' Keep this tab open for both actions.</p><button id="initial" disabled>Pay</button> '+
    '<button id="method" disabled>Confirm test method</button><p role="status" id="status">Connecting</p></main><script>'+script+'</script></html>';
  let claim=randomBytes(32).toString('hex'), session='',phase='waiting',next='initial',pending,closing;
  let origin;
  const server=createServer({maxHeaderSize:4096,requestTimeout:5000,headersTimeout:5000},(req,res)=>{
    res.setHeader('Cache-Control','no-store');
    res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'sha256-"+hash+"'; connect-src 'self'; frame-ancestors 'none'; form-action 'none'; base-uri 'none'");
    const reply=(code,text='')=>{res.statusCode=code;res.end(text);};
    if('http://'+req.headers.host!==origin||req.headers['transfer-encoding']||
        (req.headers['content-length']!==undefined&&req.headers['content-length']!=='0'))return reply(403);
    if(req.method==='GET'&&req.url==='/'){
      res.setHeader('Content-Type','text/html; charset=utf-8');return reply(200,html);
    }
    if(req.method!=='POST'||req.headers.origin!==origin)return reply(403);
    const key=req.headers['x-operator-session'];
    if(req.url==='/claim'&&claim&&key===claim){
      claim='';session=randomBytes(32).toString('hex');return reply(200,session);
    }
    if(!session||key!==session)return reply(403);
    if(req.url==='/leave'){
      session='';
      if(phase!=='done'){
        next=undefined;pending?.finish(false);phase='closed';onAbandon?.();
      }
      return reply(204);
    }
    if(req.url==='/state')return reply(200,phase);
    if(!pending||req.url!=='/'+phase||Date.now()>=pending.deadline||pending.signal.aborted)return reply(409);
    const accepted=phase;
    next=accepted==='initial'?'method':undefined;
    phase=next?'waiting':'done';
    pending.finish(true);
    reply(204);
  });
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  origin='http://127.0.0.1:'+server.address().port;
  return {
    url:origin+'/#'+claim,
    async wait(action,{deadline,signal}) {
      if(closing||!['initial','method'].includes(action)||next!==action||pending||signal.aborted||
          !Number.isSafeInteger(deadline)||deadline<=Date.now())throw denied();
      deadline=Math.min(deadline,Date.now()+180000);
      phase=action;
      await new Promise((resolve,reject)=>{
        const fail=()=>finish(false);
        const timer=setTimeout(fail,deadline-Date.now());
        function finish(ok){
          clearTimeout(timer);signal.removeEventListener('abort',fail);pending=undefined;
          if(ok)resolve();else{next=undefined;phase='closed';reject(denied());}
        }
        pending={deadline,signal,finish};
        signal.addEventListener('abort',fail,{once:true});
      });
    },
    close(){
      if(!closing){
        claim='';session='';pending?.finish(false);phase='closed';
        closing=new Promise((resolve,reject)=>server.close(e=>e?reject(e):resolve()));
        server.closeAllConnections();
      }
      return closing;
    },
  };
}

// A local, owner-readable launch file keeps the transient claim out of runner output.
export async function writeProfileHandoff(path,operator){
  await mkdir(dirname(path),{recursive:true,mode:0o700});
  const destination=new URL(operator.url);
  if(destination.origin!=='http://127.0.0.1:'+destination.port||!/^#[0-9a-f]{64}$/.test(destination.hash))throw denied();
  await writeFile(path,'<!doctype html><meta name="referrer" content="no-referrer"><meta http-equiv="refresh" content="0; url='+destination.href+'">',{flag:'wx',mode:0o600});
}
