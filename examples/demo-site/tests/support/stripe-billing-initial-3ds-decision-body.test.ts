import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import test from 'node:test';
// @ts-ignore Native Node executes the same compressed decision observer.
import {issuerDecisionBodyLimit,observeCompressedIssuerDecision} from './stripe-billing-initial-3ds-decision-body.ts';

const requestId='admitted-decision',fetchId='issuer-response-pause',frameId='issuer',loaderId='decision-loader';
const url='https://reviewed-test-issuer.stripe.com/decision';
const response={requestId,frameId,loaderId,type:'Document',response:{url,status:200,fromServiceWorker:false}};
const readFailed='BROWSER_CHALLENGE_CRES_DECISION_READ',invalidSize='BROWSER_CHALLENGE_CRES_DECISION_SIZE';
const gzipWireLength=112;

async function fixture(getBody:(id:string)=>unknown=()=>assert.fail('decoded body read must not occur')){
 const events=new EventEmitter();
 const commands:{method:string;params:unknown}[]=[];
 const guard=observeCompressedIssuerDecision({
  on:events.on.bind(events),
  send:async(method:string,params:unknown)=>{
   commands.push({method,params});
   if(method==='Network.getResponseBody')return getBody((params as {requestId:string}).requestId);
   return {};
  },
 } as never);
 await guard.enable();
 const capture=guard.observe({id:requestId,fetchId,frameId,loaderId,url,encodedLength:gzipWireLength});
 return {
  events,commands,guard,capture,
  headers:()=>events.emit('Network.responseReceived',response),
  data:(dataLength:number)=>events.emit('Network.dataReceived',{requestId,dataLength}),
  finish:(encodedDataLength=gzipWireLength+100)=>events.emit('Network.loadingFinished',{requestId,encodedDataLength}),
  reads:()=>commands.filter(({method})=>method==='Network.getResponseBody'),
 };
}

for(const base64Encoded of [false,true])test('returns exact original decoded bytes after matching data, completion and release: '+base64Encoded,async()=>{
 const prefix='<meta charset="utf-8">â';
 const bytes=Buffer.from(prefix+'x'.repeat(issuerDecisionBodyLimit-Buffer.byteLength(prefix)));
 const f=await fixture(()=>({body:bytes.toString(base64Encoded?'base64':'utf8'),base64Encoded}));
 assert.equal(bytes.length,issuerDecisionBodyLimit);
 const limits={maxTotalBufferSize:issuerDecisionBodyLimit,maxResourceBufferSize:issuerDecisionBodyLimit};
 assert.deepEqual(f.commands,[
  {method:'Network.configureDurableMessages',params:limits},
  {method:'Network.enable',params:limits},
 ]);
 f.headers();
 f.data(2000);
 f.events.emit('Network.dataReceived',{requestId:'another-request',dataLength:issuerDecisionBodyLimit*2});
 f.data(bytes.length-2000);
 f.finish();
 assert.deepEqual(f.reads(),[]);
 f.capture.released();
 assert.deepEqual(await f.capture.body,bytes);
 assert.deepEqual(f.reads(),[{method:'Network.getResponseBody',params:{requestId}}]);
});

for(const mode of ['overflow','wire overflow','wire short','missing bytes','invalid count','foreign frame','foreign loader',
 'foreign response','service worker','cache','duplicate response','duplicate pause','failed load','closed observer'])test('withholds body reads without a complete bounded decision: '+mode,async()=>{
 const f=await fixture();
 f.capture.released();
 const shape={
  ...response,frameId:mode==='foreign frame'?'other':frameId,loaderId:mode==='foreign loader'?'other':loaderId,
  response:{...response.response,url:mode==='foreign response'?url+'/other':url,fromServiceWorker:mode==='service worker'},
 };
 f.events.emit('Network.responseReceived',shape);
 if(mode==='cache')f.events.emit('Network.requestServedFromCache',{requestId});
 if(mode==='duplicate response')f.headers();
 if(mode==='duplicate pause')assert.equal(f.guard.duplicatePause(fetchId,'foreign-network-id'),true);
 if(mode==='failed load')f.events.emit('Network.loadingFailed',{requestId});
 if(mode==='closed observer')f.guard.close();
 if(mode!=='missing bytes')f.data(mode==='overflow'?issuerDecisionBodyLimit+1:mode==='invalid count'?0.5:20);
 f.finish(mode==='wire overflow'?issuerDecisionBodyLimit+1:mode==='wire short'?gzipWireLength-1:undefined);
 const size=['overflow','wire overflow','wire short','missing bytes','invalid count'].includes(mode);
 assert.equal(await f.capture.body,size?invalidSize:readFailed);
 assert.deepEqual(f.reads(),[]);
});

for(const [name,body,base64Encoded,failure] of [
 ['short body','PGh0bWw+',true,invalidSize],
 ['inflated reply','x'.repeat(66),false,invalidSize],
 ['noncanonical base64','PGh0bWw+IA',true,readFailed],
] as const)test('refuses a CDP reply inconsistent with the bounded evidence: '+name,async()=>{
 const f=await fixture(()=>({body,base64Encoded}));
 f.capture.released();
 f.headers();
 f.data(7);
 f.finish();
 assert.equal(await f.capture.body,failure);
 assert.equal(f.reads().length,1);
});

test('a retired decision cannot publish an in-flight decoded body',async()=>{
 const started=Promise.withResolvers<void>(),body=Promise.withResolvers<unknown>();
 const f=await fixture(()=>{started.resolve();return body.promise;});
 f.capture.released();
 f.headers();
 f.data(3);
 f.finish();
 await started.promise;
 f.guard.invalidate(requestId);
 assert.equal(await f.capture.body,readFailed);
 body.resolve({body:'abc',base64Encoded:false});
});

const script={id:'admitted-script',fetchId:'response-script',frameId,loaderId,url:url+'.js',resource:'Script',decodedLength:3} as const;
const scriptResponse={...response,requestId:script.id,type:'Script',response:{...response.response,url:script.url}};

test('one script and its decision can be captured independently on the same session',async()=>{
 const f=await fixture(id=>({body:id===script.id?'abc':'html',base64Encoded:false}));
 const capture=f.guard.observe(script);
 f.events.emit('Network.responseReceived',scriptResponse);
 f.events.emit('Network.dataReceived',{requestId:script.id,dataLength:3});
 f.events.emit('Network.loadingFinished',{requestId:script.id,encodedDataLength:100});
 f.headers();f.data(4);f.finish();
 f.capture.released();
 assert.deepEqual(await f.capture.body,Buffer.from('html'));
 assert.deepEqual(f.reads().map(({params})=>params),[{requestId}]);
 capture.released();
 assert.deepEqual(await capture.body,Buffer.from('abc'));
 assert.deepEqual(f.reads().map(({params})=>params),[{requestId},{requestId:script.id}]);
});

for(const [mode,expected] of [['wrong resource',readFailed],['wrong decoded length',invalidSize],['foreign loader',readFailed],
 ['cached script',readFailed],['duplicate fetch ID',readFailed]] as const)test('refuses '+mode+' without retiring the decision',async()=>{
 const f=await fixture(()=>({body:'html',base64Encoded:false}));
 const capture=f.guard.observe(script);
 f.events.emit('Network.responseReceived',{...scriptResponse,
  type:mode==='wrong resource'?'Document':scriptResponse.type,
  loaderId:mode==='foreign loader'?'other':scriptResponse.loaderId,
  response:{...scriptResponse.response,fromDiskCache:mode==='cached script'},
 });
 if(mode==='duplicate fetch ID')assert.equal(f.guard.duplicatePause(script.fetchId,'unrelated'),true);
 f.events.emit('Network.dataReceived',{requestId:script.id,dataLength:mode==='wrong decoded length'?2:3});
 f.events.emit('Network.loadingFinished',{requestId:script.id,encodedDataLength:100});
 capture.released();
 assert.equal(await capture.body,expected);
 assert.equal(f.guard.has(requestId),true);
 f.headers();f.data(4);f.finish();f.capture.released();
 assert.deepEqual(await f.capture.body,Buffer.from('html'));
 assert.deepEqual(f.reads().map(({params})=>params),[{requestId}]);
});

test('an active decision and script cannot share Network or Fetch IDs or claim another script',async()=>{
 const f=await fixture();
 assert.throws(()=>f.guard.observe({...script,id:requestId,fetchId:'another-fetch'}),/^Error: billing_BROWSER$/);
 assert.throws(()=>f.guard.observe({...script,fetchId}),/^Error: billing_BROWSER$/);
 const capture=f.guard.observe(script);
 assert.throws(()=>f.guard.observe({...script,id:'another-script',fetchId:'another-fetch'}),/^Error: billing_BROWSER$/);
 assert.equal(f.guard.duplicatePause(script.fetchId,requestId),true);
 assert.equal(await f.capture.body,readFailed);
 assert.equal(await capture.body,readFailed);
 assert.deepEqual(f.reads(),[]);
});

for(const [mode,body,base64Encoded,asciiOnly,expected] of [
 ['ambiguous CDP text replacement','\uFFFD',false,false,readFailed],
 ['non-ASCII decoded CDP text with explicit UTF-8','€',false,false,readFailed],
 ['exact non-ASCII bytes without an explicit charset','€',true,true,readFailed],
 ['exact non-ASCII bytes with explicit UTF-8','€',true,false,Buffer.from('€')],
 ['ASCII script without an explicit charset','abc',false,true,Buffer.from('abc')],
] as const)test('validates the exact script encoding: '+mode,async()=>{
 const f=await fixture(()=>({body:base64Encoded?Buffer.from(body).toString('base64'):body,base64Encoded}));
 const capture=f.guard.observe({...script,asciiOnly});
 f.events.emit('Network.responseReceived',scriptResponse);
 f.events.emit('Network.dataReceived',{requestId:script.id,dataLength:Buffer.byteLength(body)});
 f.events.emit('Network.loadingFinished',{requestId:script.id,encodedDataLength:100});
 capture.released();
 assert.deepEqual(await capture.body,expected);
 f.guard.close();
});

for(const overflow of ['encoded','decoded'])test('does not read a script beyond the '+overflow+' byte cap',async()=>{
 const f=await fixture();
 const capture=f.guard.observe({...script,decodedLength:undefined});
 f.events.emit('Network.responseReceived',scriptResponse);
 f.events.emit('Network.dataReceived',{requestId:script.id,dataLength:overflow==='decoded'?issuerDecisionBodyLimit+1:3});
 f.events.emit('Network.loadingFinished',{requestId:script.id,encodedDataLength:overflow==='encoded'?issuerDecisionBodyLimit+1:100});
 capture.released();
 assert.equal(await capture.body,invalidSize);
 assert.deepEqual(f.reads(),[]);
 f.guard.close();
});
