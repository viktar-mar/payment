import {createHash,randomBytes} from 'node:crypto';
import {createServer} from 'node:http';
import {closeSync,linkSync,lstatSync,openSync,unlinkSync,writeFileSync} from 'node:fs';
import {mkdir,writeFile} from 'node:fs/promises';
import {dirname} from 'node:path';

function operateTab() {
  let token=location.hash.slice(1), claimed=false, finished=false, busy=false;
  let pollTimer, readTimer, activeRead, retryUsed=false;
  history.replaceState(null,'',location.pathname);
  const status=document.getElementById('status');
  const buttons=[...document.querySelectorAll('button')];
  const lock=()=>buttons.forEach(button=>{button.disabled=true;});
  function cancelRead() {
    clearTimeout(pollTimer);
    clearTimeout(readTimer);
    const previous=activeRead;
    activeRead=undefined;
    previous?.abort();
  }
  function leave(session) {
    void fetch('/leave',{method:'POST',credentials:'omit',redirect:'error',
      keepalive:true,headers:{'X-Operator-Session':session}}).catch(()=>{});
  }
  function stop() {
    if(finished)return;
    finished=true;
    cancelRead();
    if(claimed)leave(token);
    token='';lock();status.textContent='Session closed';
  }
  window.addEventListener('pagehide',stop,{once:true});
  async function post(path,signal) {
    const reply=await fetch('/'+path,{method:'POST',credentials:'omit',cache:'no-store',redirect:'error',signal,
      headers:{'X-Operator-Session':token}});
    if(!reply.ok)throw Error();
    return reply.text();
  }
  async function refresh() {
    if(!claimed||finished||busy||activeRead)return;
    clearTimeout(pollTimer);
    const current=new AbortController();
    activeRead=current;
    // Bound both receipt of the headers and consumption of the body. Only a state read is repeatable.
    readTimer=setTimeout(()=>{
      if(activeRead!==current)return;
      if(retryUsed){stop();return;}
      retryUsed=true;
      cancelRead();
      lock();
      status.textContent='Reconnecting to the test form';
      pollTimer=setTimeout(refresh,500);
    },3000);
    try {
      const phase=await post('state',current.signal);
      if(activeRead!==current)return;
      lock();
      if(phase==='initial'||phase==='method'||phase==='challenge'){
        document.getElementById(phase).disabled=false;
        status.textContent=phase==='challenge'?'The issuer challenge is ready':phase==='initial'?'Ready for Pay':'Ready to confirm the replacement method';
      }else if(phase==='done'){
        cancelRead();finished=true;token='';status.textContent=document.getElementById('challenge')?'Challenge click received. The runner verifies the result.':'Both clicks received. The runner verifies the result.';return;
      }else if(phase==='waiting')status.textContent='Waiting for the test form';
      else throw Error();
      clearTimeout(readTimer);
      activeRead=undefined;
      retryUsed=false;
      pollTimer=setTimeout(refresh,500);
    }catch{if(activeRead===current)stop();}
  }
  const resume=()=>{if(document.visibilityState==='visible')void refresh();};
  window.addEventListener('focus',resume);
  document.addEventListener('visibilitychange',resume);
  for(const button of buttons)button.addEventListener('click',async event=>{
    if(!event.isTrusted||button.disabled||busy||finished||document.visibilityState!=='visible')return;
    busy=true;cancelRead();lock();
    try{await post(button.id);busy=false;void refresh();}catch{stop();}
  });
  post('claim').then(value=>{
    if(finished){leave(value);return;}
    token=value;claimed=true;void refresh();
  },stop);
}

const script='('+operateTab.toString()+')();';
const hash=createHash('sha256').update(script).digest('base64');
const denied=()=>new Error('Profile checkout closed');

export async function openProfileOperator({rehearsal,onAbandon,handoffPath,mode='recovery'}) {
  const challenge=mode==='challenge';
  if(!['recovery','challenge'].includes(mode)||typeof rehearsal!=='boolean')throw denied();
  if(handoffPath!==undefined){
    if(rehearsal || typeof handoffPath!=='string' || !handoffPath)throw denied();
    await mkdir(dirname(handoffPath),{recursive:true,mode:0o700});
    if(lstatSync(handoffPath,{throwIfNoEntry:false}))throw denied();
  }
  const title=challenge?'Stripe test issuer challenge':rehearsal?'Checkout rehearsal':'Stripe Sandbox recovery';
  const description=rehearsal?'Local simulation. No charge.':'Test subscription: initial payment €20, then recovery of the original €20 debt.';
  const controls=challenge?'Confirm the test issuer challenge for this subscription. Keep this tab open.</p><button id="challenge" disabled>Complete issuer challenge</button>':
    description+' Keep this tab open for both actions.</p><button id="initial" disabled>Pay</button> <button id="method" disabled>Confirm test method</button>';
  const html='<!doctype html><html lang="en"><meta charset="utf-8"><title>'+title+'</title>'+
    '<main><h1>'+title+'</h1><p>'+controls+'<p role="status" id="status">Connecting</p></main><script>'+script+'</script></html>';
  let claim=randomBytes(32).toString('hex'), session='',phase='waiting',next=challenge?'challenge':'initial',pending,closing;
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
  const url=origin+'/#'+claim;
  return {
    url,
    async wait(action,{deadline,signal}) {
      if(closing||!(challenge?['challenge']:['initial','method']).includes(action)||next!==action||pending||signal.aborted||
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
        if(action===(challenge?'challenge':'initial') && handoffPath){
          try{publishArmedHandoff(handoffPath,url,deadline,signal);}
          catch{claim='';fail();}
        }
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
function handoffHtml(url){
  const destination=new URL(url);
  if(destination.origin!=='http://127.0.0.1:'+destination.port||!/^#[0-9a-f]{64}$/.test(destination.hash))throw denied();
  return '<!doctype html><meta name="referrer" content="no-referrer"><meta http-equiv="refresh" content="0; url='+destination.href+'">';
}

function publishArmedHandoff(path,url,deadline,signal){
  // The launcher can open the path as soon as it exists. Link the complete
  // file exclusively while the same wait still owns the action.
  const temporary=path+'.'+randomBytes(16).toString('hex');
  const fd=openSync(temporary,'wx',0o600);
  try{
    try{writeFileSync(fd,handoffHtml(url));}finally{closeSync(fd);}
    if(signal.aborted || Date.now()>=deadline)throw denied();
    linkSync(temporary,path);
  }finally{unlinkSync(temporary);}
}

export async function writeProfileHandoff(path,operator){
  await mkdir(dirname(path),{recursive:true,mode:0o700});
  await writeFile(path,handoffHtml(operator.url),{flag:'wx',mode:0o600});
}
