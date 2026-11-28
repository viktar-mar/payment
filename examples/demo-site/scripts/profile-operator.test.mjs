import test from 'node:test';
import assert from 'node:assert/strict';
import {openProfileOperator} from './profile-operator.mjs';
import {forwardProfileClick} from '../tests/support/profile-button.ts';

test('forwarding revalidates the original target after the click arrives',async()=>{
  for(const mode of ['valid','stale','disabled','aborted','refused','abort-on-focus','stale-on-focus']){
    const controller=new AbortController();
    const calls=[];
    const target={
      isEnabled:async()=>mode!=='disabled',
      async focus(){
        calls.push('focused');
        if(mode==='abort-on-focus')controller.abort();
      },
      async click(){calls.push('clicked');},
    };
    const forwarded=forwardProfileClick(target,'method',{deadline:Date.now()+2000,signal:controller.signal},
      async which=>{
        assert.equal(which,'method');
        if(mode==='refused')throw Error('closed');
        if(mode==='aborted')controller.abort();
      },()=>{
        if(mode==='stale'||(mode==='stale-on-focus'&&calls.includes('focused')))throw Error('stale');
      });
    if(mode==='valid')await forwarded;else await assert.rejects(forwarded);
    assert.deepEqual(calls,mode==='valid'?['focused','clicked']:mode.endsWith('-on-focus')?['focused']:[]);
  }
});

test('one tab claims the rehearsal and can release only the currently armed action once', async () => {
  const operator = await openProfileOperator({rehearsal:true});
  const url = new URL(operator.url);
  const send = (path, key, origin = url.origin) => fetch(url.origin + path, {method:'POST',
    headers:{Origin:origin, 'X-Operator-Session':key}, redirect:'error'});
  try {
    const claim = () => send('/claim', url.hash.slice(1));
    assert.equal((await send('/claim', url.hash.slice(1), 'https://example.invalid')).status,403);
    const reply = await claim();
    assert.equal(reply.status,200);
    const token = await reply.text();
    assert.equal((await claim()).status,403);
    assert.equal((await send('/initial', token)).status,409);
    const limits = {deadline:Date.now()+15000,signal:new AbortController().signal};
    const first = operator.wait('initial',limits);
    assert.equal(await (await send('/state', token)).text(),'initial');
    assert.equal((await send('/method', token)).status,409);
    assert.equal((await send('/initial', url.hash.slice(1))).status,403);
    const replies=await Promise.all([send('/initial',token),send('/initial',token)]);
    assert.deepEqual(replies.map(r=>r.status).sort(),[204,409]);
    await first;
    const second = operator.wait('method',limits);
    assert.equal((await send('/method',token)).status,204);
    await second;
    assert.equal(await (await send('/state', token)).text(),'done');
    await assert.rejects(operator.wait('method',limits));
  } finally {await operator.close();}
});

test('expiry, abort and close cannot release a pending click',async()=>{
  for(const reason of ['expired','abort','closed']){
    const operator=await openProfileOperator({rehearsal:true});
    try{
      const ac=new AbortController();
      const waiting=operator.wait('initial',{deadline:Date.now()+(reason==='expired'?5:10000),signal:ac.signal});
      const rejected=assert.rejects(waiting);
      if(reason==='abort')ac.abort();
      if(reason==='closed')await operator.close();
      await rejected;
      await assert.rejects(operator.wait('initial',{deadline:Date.now()+5000,signal:new AbortController().signal}));
    }finally{await operator.close();}
  }
});

test('leaving the claimed tab revokes the pending action and notifies the owner once',async()=>{
  let abandoned=0;
  const operator=await openProfileOperator({rehearsal:true,onAbandon:()=>abandoned++});
  try{
    const url=new URL(operator.url);
    const post=(path,key)=>fetch(url.origin+path,{method:'POST',headers:{Origin:url.origin,'X-Operator-Session':key}});
    const token=await (await post('/claim',url.hash.slice(1))).text();
    const waiting=assert.rejects(operator.wait('initial',{deadline:Date.now()+5000,signal:new AbortController().signal}));
    assert.equal((await post('/leave',token)).status,204);
    await waiting;
    assert.equal((await post('/initial',token)).status,403);
    assert.equal((await post('/leave',token)).status,403);
    assert.equal(abandoned,1);
  }finally{await operator.close();}
});

test('the issuer-only profile handoff accepts exactly one challenge gesture and no payment or replacement',async()=>{
  const operator=await openProfileOperator({rehearsal:true,mode:'challenge'});
  try{
    const url=new URL(operator.url),post=(path,key)=>fetch(url.origin+'/'+path,{method:'POST',headers:{Origin:url.origin,'X-Operator-Session':key}});
    const html=await (await fetch(url.origin)).text();
    assert.match(html,/<button id="challenge" disabled>/);assert.doesNotMatch(html,/<button id="(?:method|initial)"/);
    const token=await (await post('claim',url.hash.slice(1))).text();
    const limits={deadline:Date.now()+1000,signal:new AbortController().signal};
    await assert.rejects(operator.wait('initial',limits));
    const wait=operator.wait('challenge',limits);void wait.catch(()=>{});
    assert.equal((await post('method',token)).status,409);assert.equal((await post('initial',token)).status,409);
    assert.equal((await post('challenge',token)).status,204);await wait;
    assert.equal((await post('challenge',token)).status,409);
    await assert.rejects(operator.wait('challenge',limits));
  }finally{await operator.close();}
});
