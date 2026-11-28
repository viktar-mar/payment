import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import http from 'node:http';
import {syncBuiltinESMExports} from 'node:module';
import test, {before} from 'node:test';
import vm from 'node:vm';
import {openProfileOperator} from './profile-operator.mjs';

let script;
before(async t=>{
  // Capture the exact script supplied by the rehearsal HTTP handler without listening on a socket.
  let dispatch;
  const server=Object.assign(new EventEmitter(),{
    listen(_port,_host,ready){ready();},address:()=>({port:41000}),
    close(done){done();},closeAllConnections(){},
  });
  const stub=t.mock.method(http,'createServer',(_options,handler)=>{dispatch=handler;return server;});
  syncBuiltinESMExports();
  try{
    const operator=await openProfileOperator({rehearsal:true});
    try{
      let html;
      dispatch({method:'GET',url:'/',headers:{host:new URL(operator.url).host}},
        {setHeader(){},end(body){html=body;}});
      const scripts=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
      assert.equal(scripts.length,1);
      script=new vm.Script(scripts[0][1]);
    }finally{await operator.close();}
  }finally{stub.mock.restore();syncBuiltinESMExports();}
});

const settle=()=>new Promise(resolve=>setImmediate(resolve));
const reply=text=>({ok:true,text:async()=>text});
function gesture(button){
  const click=new Event('click');
  Object.defineProperty(click,'isTrusted',{value:true});
  button.dispatchEvent(click);
}

async function openTab(respond){
  const document=new EventTarget(),window=new EventTarget();
  const buttons=['initial','method'].map(id=>Object.assign(new EventTarget(),{id,disabled:true}));
  const status={textContent:'Connecting'},[pay,method]=buttons;
  document.visibilityState='visible';
  document.getElementById=id=>id==='status'?status:buttons.find(button=>button.id===id);
  document.querySelectorAll=()=>buttons;
  let now=0,sequence=0;
  const timers=new Map(),requests=[];
  function setTimeout(fn,delay){
    const id=++sequence;
    timers.set(id,{fn,at:now+delay});
    return id;
  }
  async function advance(ms){
    const end=now+ms;
    for(;;){
      const first=[...timers.entries()].sort((a,b)=>a[1].at-b[1].at)[0];
      if(!first||first[1].at>end)break;
      const [id,{fn,at}]=first;
      timers.delete(id);now=at;fn();await settle();
    }
    now=end;
  }
  script.runInNewContext({
    window,document,history:{replaceState(){}},location:{hash:'#rehearsal',pathname:'/'},
    setTimeout,clearTimeout:id=>timers.delete(id),AbortController,
    fetch:async(path,options)=>{requests.push(path);return respond(path,options);},
  });
  await settle();
  return {pay,method,status,window,document,requests,advance};
}

for(const held of ['response','body'])test('a stalled state '+held+' cannot conceal a newly armed Pay',async()=>{
  let phase='waiting',reads=0;
  const late=Promise.withResolvers();
  const tab=await openTab(path=>{
    if(path==='/claim')return reply('session');
    assert.equal(path,'/state');
    if(++reads===2){
      return held==='response'?late.promise:{ok:true,text:()=>late.promise};
    }
    return reply(phase);
  });
  assert.equal(tab.status.textContent,'Waiting for the test form');
  await tab.advance(1000);
  assert.equal(reads,2);
  phase='initial';
  await tab.advance(20000);
  assert.equal(tab.pay.disabled,false,'a newer state read must enable Pay within the operator window');
  assert.equal(tab.method.disabled,true);
  late.resolve(held==='response'?reply('waiting'):'waiting');
  await settle();
  assert.equal(tab.pay.disabled,false,'a previous waiting response must not relock Pay');
  assert.deepEqual([...new Set(tab.requests)],['/claim','/state']);
});

for(const event of ['visibilitychange','focus'])test(event+' immediately refreshes a waiting tab',async()=>{
  let phase='waiting';
  const tab=await openTab(path=>reply(path==='/claim'?'session':phase));
  assert.equal(tab.status.textContent,'Waiting for the test form');
  tab.document.visibilityState='hidden';
  tab.document.dispatchEvent(new Event('visibilitychange'));
  await settle();
  phase='initial';
  tab.document.visibilityState='visible';
  (event==='focus'?tab.window:tab.document).dispatchEvent(new Event(event));
  // Allow an immediate refresh without advancing the regular polling interval.
  await tab.advance(0);
  await settle();
  assert.equal(tab.pay.disabled,false);
  assert.deepEqual([...new Set(tab.requests)],['/claim','/state']);
});

for(const failure of ['/claim','/state','/initial'])test('a refused '+failure+' is terminal',async()=>{
  const tab=await openTab(path=>path===failure?{ok:false,status:403}:reply(path==='/claim'?'session':'initial'));
  if(failure==='/initial'){
    assert.equal(tab.pay.disabled,false);
    gesture(tab.pay);await settle();
  }
  await tab.advance(20000);
  tab.window.dispatchEvent(new Event('focus'));await settle();
  assert.equal(tab.requests.filter(path=>path===failure).length,1);
  assert.equal(tab.pay.disabled,true);
  assert.equal(tab.status.textContent,'Session closed');
});

test('pagehide revokes the tab even if an earlier state response arrives later',async()=>{
  const late=Promise.withResolvers();
  const tab=await openTab(path=>path==='/state'?late.promise:reply('session'));
  tab.window.dispatchEvent(new Event('pagehide'));
  late.resolve(reply('initial'));await settle();
  tab.window.dispatchEvent(new Event('focus'));
  await tab.advance(20000);
  assert.deepEqual(tab.requests,['/claim','/state','/leave']);
  assert.equal(tab.pay.disabled,true);
  assert.equal(tab.status.textContent,'Session closed');
});

test('closing the tab during claim relinquishes a late session without reading or enabling Pay',async()=>{
  const claim=Promise.withResolvers();
  let releasedSession;
  const tab=await openTab((path,options)=>{
    if(path==='/claim')return claim.promise;
    if(path==='/leave')releasedSession=options.headers['X-Operator-Session'];
    return reply('');
  });
  tab.window.dispatchEvent(new Event('pagehide'));
  claim.resolve(reply('late-session'));await settle();
  tab.window.dispatchEvent(new Event('focus'));
  await tab.advance(10000);
  assert.deepEqual(tab.requests,['/claim','/leave']);
  assert.equal(releasedSession,'late-session');
  assert.equal(tab.pay.disabled,true);
});

test('two consecutive state timeouts revoke a previously enabled Pay',async()=>{
  let reads=0;
  const pending=[];
  const tab=await openTab(path=>{
    if(path==='/claim')return reply('session');
    if(path==='/leave')return reply('');
    if(++reads===1)return reply('initial');
    const stalled=Promise.withResolvers();
    pending.push(stalled);
    return stalled.promise;
  });
  assert.equal(tab.pay.disabled,false);
  await tab.advance(5000);
  assert.equal(tab.pay.disabled,true,'the first timeout locks the old control during retry');
  assert.equal(tab.method.disabled,true);
  await tab.advance(30000);
  assert.equal(tab.status.textContent,'Session closed');
  const requestsBeforeFocus=[...tab.requests];
  assert.deepEqual(requestsBeforeFocus,['/claim','/state','/state','/state','/leave']);
  for(const stalled of pending)stalled.resolve(reply('initial'));
  tab.window.dispatchEvent(new Event('pagehide'));
  tab.window.dispatchEvent(new Event('focus'));await settle();
  assert.deepEqual(tab.requests,requestsBeforeFocus);
  assert.equal(tab.pay.disabled,true);
});

test('a successful state read resets the consecutive timeout budget',async()=>{
  let reads=0;
  const held=new Set([2,4]);
  const tab=await openTab(path=>{
    if(path==='/claim')return reply('session');
    if(held.has(++reads))return new Promise(()=>{});
    return reply(reads>=5?'initial':'waiting');
  });
  await tab.advance(20000);
  assert.ok(reads>=5);
  assert.equal(tab.pay.disabled,false);
  assert.equal(tab.method.disabled,true);
  assert.deepEqual([...new Set(tab.requests)],['/claim','/state']);
});

test('repeated focus cannot extend a stalled read or preserve a stale enabled button',async()=>{
  let reads=0;
  const tab=await openTab(path=>{
    if(path==='/claim')return reply('session');
    if(path==='/leave')return reply('');
    return ++reads===1?reply('initial'):new Promise(()=>{});
  });
  assert.equal(tab.pay.disabled,false);
  for(let i=0;i<10;i++){
    await tab.advance(1000);
    tab.window.dispatchEvent(new Event('focus'));
    await settle();
  }
  assert.equal(reads,3);
  assert.equal(tab.pay.disabled,true);
  assert.equal(tab.status.textContent,'Session closed');
});

test('a late initial state cannot re-enable Pay after its one click',async()=>{
  const oldRead=Promise.withResolvers(),payAck=Promise.withResolvers();
  let phase='initial',reads=0;
  const tab=await openTab(path=>{
    if(path==='/claim')return reply('session');
    if(path==='/state')return ++reads===2?oldRead.promise:reply(phase);
    if(path==='/initial')return payAck.promise;
    assert.equal(path,'/method');
    phase='done';
    return reply('');
  });
  await tab.advance(500);
  assert.equal(reads,2);
  assert.equal(tab.pay.disabled,false);
  gesture(tab.pay);
  gesture(tab.pay);
  tab.window.dispatchEvent(new Event('focus'));
  await settle();
  assert.equal(reads,2);
  assert.equal(tab.pay.disabled,true);
  assert.equal(tab.method.disabled,true);
  phase='method';
  payAck.resolve(reply(''));await settle();
  assert.equal(tab.method.disabled,false);
  oldRead.resolve(reply('initial'));await settle();
  assert.equal(tab.pay.disabled,true);
  assert.equal(tab.method.disabled,false);
  gesture(tab.method);
  gesture(tab.method);
  await settle();
  assert.equal(tab.method.disabled,true);
  assert.deepEqual(tab.requests.filter(path=>path!=='/state'),['/claim','/initial','/method']);
});

test('the same tab exposes only the replacement action when focused after Pay',async()=>{
  let phase='initial';
  const tab=await openTab(path=>{
    if(path==='/claim')return reply('session');
    if(path==='/state')return reply(phase);
    if(path==='/initial')phase='waiting';
    if(path==='/method')phase='done';
    return reply('');
  });
  assert.equal(tab.pay.disabled,false);
  gesture(tab.pay);await settle();
  assert.equal(tab.status.textContent,'Waiting for the test form');
  tab.document.visibilityState='hidden';
  tab.document.dispatchEvent(new Event('visibilitychange'));
  phase='method';
  // A background tab can be suspended before its next poll. Returning to
  // it must discover the pending method without a reload or another claim.
  assert.equal(tab.method.disabled,true);
  tab.document.visibilityState='visible';
  tab.document.dispatchEvent(new Event('visibilitychange'));
  await settle();
  assert.equal(tab.pay.disabled,true);
  assert.equal(tab.method.disabled,false);
  gesture(tab.method);
  gesture(tab.method);
  await settle();
  assert.equal(tab.method.disabled,true);
  assert.deepEqual(tab.requests.filter(path=>path!=='/state'),['/claim','/initial','/method']);
});
