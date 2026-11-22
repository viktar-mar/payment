import test from 'node:test';
import {id} from './fixtures/failed-renewal-descriptors.mjs';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {createOwnedInvoiceIngress,ownedInvoiceForwardTarget} from './stripe-billing-invoice-ingress.mjs';

import {syntheticInvoiceIngress,config,local,owner,refs,secret} from './fixtures/invoice-ingress-harness.mjs';
const limits=()=>({deadline:Date.now()+2000});
async function harness(onFailure){
 const h=syntheticInvoiceIngress(config,onFailure);await h.ingress.open(limits());h.ingress.signingKey(secret);return h;
}
test('two signed originals are retained in memory and delivered once in reverse order to the fixed owned backend',async()=>{
 const h=await harness();h.ingress.arm(local);
 const a=await h.post(h.event('finalized'));assert.equal(a.status,204);h.advance(1000);
 const finalized=await h.ingress.beforeConfirm(owner,refs,limits());assert.equal(finalized.eventId,'evt_finalized');
 h.ingress.releaseConfirm();
 const b=await h.post(h.event('paid'));assert.equal(b.status,204);
 const pair=await h.ingress.pair(limits());assert.equal(pair.paid.eventId,'evt_paid');assert.equal(h.sent.length,0);
 await h.ingress.deliver('paid',limits());await h.ingress.deliver('finalized',limits());
 assert.deepEqual(h.sent,[b,a].map(e=>({url:'http://127.0.0.1:18080/api/v1/provider-webhooks/stripe/stripe-sandbox-default',raw:e.raw,sig:e.sig})));
 await assert.rejects(h.ingress.deliver('paid',limits()));assert.equal(await h.ingress.close(),true);
 assert.throws(()=>ownedInvoiceForwardTarget(h.ingress,config));
});
for(const mode of ['signature','stale','duplicate signature','foreign metadata','wrong type','early paid','duplicate JSON','foreign grant','expired release','lost backend','repeat','wrong order']){
 test('ingress stops safely on '+mode,async()=>{
  const h=await harness();h.ingress.arm(local);
  const event=h.event(mode==='early paid'?'paid':'finalized');
  if(mode==='foreign metadata')event.data.object.parent.subscription_details.metadata.site_id=id(30);
  if(mode==='wrong type')event.type='invoice.updated';
  const a=await h.post(event,{invalid:mode==='signature',old:mode==='stale',repeat:mode==='duplicate signature',
   ...(mode==='duplicate JSON'?{content:JSON.stringify(event).replace('"livemode":false','"livemode":true,"livemode":false')}:{})});
  if(['signature','stale','duplicate signature','foreign metadata','wrong type','early paid','duplicate JSON'].includes(mode))assert.notEqual(a.status,204);
  else{
   assert.equal(a.status,204);h.advance(1000);
   if(mode==='foreign grant')await assert.rejects(h.ingress.beforeConfirm(owner,{...refs,invoiceReference:'in_foreign'},limits()));
   else{
    await h.ingress.beforeConfirm(owner,refs,limits());h.ingress.releaseConfirm();await h.post(h.event('paid'));await h.ingress.pair(limits());
    if(mode==='expired release')h.advance(301000);
    if(mode==='lost backend')h.lose();
    if(mode==='repeat')assert.notEqual((await h.post(h.event('paid'))).status,204);
    await assert.rejects(h.ingress.deliver(mode==='wrong order'?'finalized':'paid',limits()));
   }
  }
  assert.equal(h.sent.length,0);await h.ingress.close();
 });
}
test('finish closes the exact receiver and rejects any taint after its last dispatch',async()=>{
 for(const late of [false,true]){
  const h=await harness();h.ingress.arm(local);await h.post(h.event('finalized'));h.advance(1000);
  await h.ingress.beforeConfirm(owner,refs,limits());h.ingress.releaseConfirm();await h.post(h.event('paid'));await h.ingress.pair(limits());
  await h.ingress.deliver('paid',limits());await h.ingress.deliver('finalized',limits());
  if(late){await h.post(h.event('paid'));await assert.rejects(h.ingress.finish(limits()));}
  else{assert.equal(await h.ingress.finish(limits()),true);assert.throws(()=>ownedInvoiceForwardTarget(h.ingress,config));}
  assert.equal(await h.ingress.close(),true);
 }
});
test('closing during asynchronous bind waits until the exact receiver closes',async()=>{
 let ready,done,closing=false;
 const server=Object.assign(new EventEmitter(),{listening:false,address:()=>({address:'127.0.0.1',port:25555}),
  listen(_port,_host,cb){ready=()=>{this.listening=true;cb();};},close(cb){closing=true;done=()=>{this.listening=false;cb();};},closeAllConnections(){}});
 const ingress=createOwnedInvoiceIngress(config,{}, {createServer:()=>server});
 const opening=ingress.open(limits());void opening.catch(()=>{});
 await new Promise(r=>setImmediate(r));
 let closed=false;const stopping=ingress.close().then(v=>{closed=true;return v;});
 await new Promise(r=>setImmediate(r));assert.equal(closed,false);assert.equal(closing,false);
 ready();await new Promise(r=>setImmediate(r));assert.equal(closing,true);assert.equal(closed,false);done();
 assert.equal(await stopping,true);assert.equal(server.listening,false);await assert.rejects(opening);
});

test('aborted ingress synchronously revokes the reserved confirm and administrative boundary',async()=>{
 for(const point of ['confirm','reconcile']){
  const cancellation=new AbortController(),h=await harness(()=>cancellation.abort());
  h.ingress.arm(local);await h.post(h.event('finalized'));h.advance(1000);await h.ingress.beforeConfirm(owner,refs,limits());
  if(point==='reconcile'){h.ingress.releaseConfirm();await h.post(h.event('paid'));await h.ingress.pair(limits());}
  await h.post(h.event('finalized'));assert.equal(cancellation.signal.aborted,true);
  assert.throws(()=>point==='confirm'?h.ingress.releaseConfirm():h.ingress.requirePair());assert.equal(h.sent.length,0);await h.ingress.close();
 }
});
test('exact target includes the whole run topology and HTTP parser failures revoke the owner',async()=>{
 let tainted=0,receive;
 const server=Object.assign(new EventEmitter(),{listening:false,address:()=>({address:'127.0.0.1',port:25555}),
  listen(_port,_host,cb){this.listening=true;cb();},close(cb){this.listening=false;cb();},closeAllConnections(){}});
 const h=createOwnedInvoiceIngress(config,{}, {onFailure:()=>{tainted++;},createServer:(_opts,cb)=>{receive=cb;return server;}});
 await h.open(limits());assert.ok(receive);
 for(const alternate of [{...config,daemon:'unix:///foreign'},{...config,ports:{...config.ports,demo:13456}}])assert.throws(()=>ownedInvoiceForwardTarget(h,alternate));
 let destroyed=false;server.emit('clientError',Error('malformed'),{destroy(){destroyed=true;}});
 assert.equal(tainted,1);assert.equal(destroyed,true);assert.throws(()=>ownedInvoiceForwardTarget(h,config));await h.close();
});
for(const event of ['checkContinue','checkExpectation','connect','upgrade']){
 test('unexpected HTTP server event revokes the current receiver: '+event,async()=>{
  const server=Object.assign(new EventEmitter(),{listening:false,address:()=>({address:'127.0.0.1',port:25555}),
   listen(_port,_host,cb){this.listening=true;cb();},close(cb){this.listening=false;cb();},closeAllConnections(){}});
  let revoked=false,closed=false;
  const receiver=createOwnedInvoiceIngress(config,{}, {createServer:()=>server,onFailure:()=>{revoked=true;}});
  await receiver.open(limits());
  server.emit(event,{}, {destroy(){closed=true;}});
  assert.equal(revoked,true);
  assert.equal(closed,true);
  assert.throws(()=>ownedInvoiceForwardTarget(receiver,config));
  await receiver.close();
 });
}
