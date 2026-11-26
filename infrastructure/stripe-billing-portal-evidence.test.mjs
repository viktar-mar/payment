import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {readOnlyBillingPortalSql} from './stripe-billing-portal-evidence.mjs';

const query=await readFile(new URL('./stripe-billing-portal-proof.sql',import.meta.url),'utf8');
const id=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const proofArgs=[
 ...Array.from({length:8},(_,i)=>id(i+1)),
 1,'a'.repeat(64),'http://127.0.0.1:18081/?site_key=s5a-00000000000040008000000000000001',
 '2099-01-01T00:10:00.123456Z',3,'2099-01-01T00:00:00.000Z','2099-02-01T00:00:00.000Z',
];
const request=(args=proofArgs)=>Object.freeze({mode:'read only',query,args:Object.freeze([...args])});
const failure={message:'billing_PORTAL_EVIDENCE'};

function harness(overrides={}) {
 const events=[],credentials={host:'127.0.0.1',port:15432,database:'payment',user:'payment',password:'private-test-value'};
 let leased=false,closed=false,active;
 const bootstrap={async withDatabaseCredentials(use,limits){
  events.push('lease');active=limits;leased=true;
  try {return await use(credentials);}
  finally {leased=false;}
 }};
 const connect=options=>{
  events.push('connect');
  assert.ok(leased&&!closed);
  assert.deepEqual({...options,onnotice:typeof options.onnotice},{
   ...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:'function',debug:false,
  });
  return {
   async begin(mode,execute) {
    assert.equal(mode,'read only');
    return execute({async unsafe(sql,args){
     assert.ok(leased&&!closed);
     if(sql==="set local statement_timeout='5000'"){events.push('statement timeout');return overrides.timeout?.();}
     events.push('proof');
     assert.equal(sql,query);
     assert.deepEqual(args,proofArgs);
     assert.ok(Object.isFrozen(args));
     return overrides.read?overrides.read():[{portal_verified:true}];
    }});
   },
   async end(options){assert.deepEqual(options,{timeout:0});assert.equal(closed,false);closed=true;events.push('close');overrides.close?.();},
  };
 };
 return {events,bootstrap,active:()=>active,run:(input=request(),limits={deadline:Date.now()+2000})=>
  readOnlyBillingPortalSql({bootstrap,request:input},limits,{connect})};
}

test('reads exactly the pinned Portal proof under the owned lease and preserves UTC microseconds',async()=>{
 const f=harness(),deadline=Date.now()+2000;
 assert.deepEqual(await f.run(request(),{deadline}),[{portal_verified:true}]);
 assert.ok(f.active().deadline<=deadline);
 assert.deepEqual(f.events,['lease','connect','statement timeout','proof','close']);
});

test('refuses modified or malformed Portal SQL inputs before leasing database credentials',async()=>{
 const withArg=(i,value)=>request(proofArgs.map((original,index)=>index===i?value:original));
 const valid=request();
 const bad=[
  {...valid},Object.freeze({...valid,args:[...proofArgs]}),
  Object.freeze({...valid,mode:'read write'}),
  Object.freeze({...valid,query:query+'\n'}),
  Object.freeze({...valid,extra:true}),
  Object.freeze({...valid,args:Object.freeze(proofArgs.slice(0,-1))}),
  withArg(7,'not-a-uuid'),withArg(8,'1'),withArg(9,'a'.repeat(63)),withArg(10,'https://127.0.0.1:18081/'),
  withArg(10,'http://127.0.0.1:18081/#unexpected'),withArg(11,'2099-02-30T00:10:00Z'),withArg(11,'2099-01-01T00:10:00.1234567Z'),
  withArg(12,-1),withArg(14,proofArgs[13]),
 ];
 const f=harness();
 for(const input of bad)await assert.rejects(f.run(input),failure);
 assert.deepEqual(f.events,[]);
});

test('rejects accessor inputs and proxies without invoking their traps',async()=>{
 const valid=request();
 const never=()=>assert.fail('input code executed');
 const accessor=Object.freeze({...valid,get query(){return never();}});
 const unsafeArg=[...proofArgs];
 Object.defineProperty(unsafeArg,'0',{enumerable:true,get:never});
 const f=harness();
 for(const input of [accessor,Object.freeze({...valid,args:Object.freeze(unsafeArg)}),new Proxy(valid,{ownKeys:never})])
  await assert.rejects(f.run(input),failure);
 assert.deepEqual(f.events,[]);
});

test('only one exact true row passes and database refusal text stays private',async()=>{
 for(const rows of [[],[{portal_verified:false}],[{portal_verified:'true'}],[{portal_verified:true},{portal_verified:true}],[{portal_verified:true,raw:'private-test-value'}]]) {
  const f=harness({read:()=>rows});
  await assert.rejects(f.run(),failure);
  assert.equal(f.events.at(-1),'close');
 }
 const f=harness({read:()=>{throw Error('private-test-value');}});
 await assert.rejects(f.run(),failure);
 assert.equal(f.events.at(-1),'close');
});

test('aborting Portal proof closes the connection once and prevents a late proof',async()=>{
 const entered=Promise.withResolvers(),pending=Promise.withResolvers(),controller=new AbortController();
 const f=harness({read:()=>{entered.resolve();return pending.promise;},close:()=>pending.resolve([{portal_verified:true}])});
 const failed=assert.rejects(f.run(request(),{deadline:Date.now()+2000,signal:controller.signal}),failure);
 await entered.promise;
 controller.abort();
 await failed;
 assert.deepEqual(f.events,['lease','connect','statement timeout','proof','close']);
});

test('an aborted statement-timeout setup never forwards the proof query',async()=>{
 const entered=Promise.withResolvers(),pending=Promise.withResolvers(),controller=new AbortController();
 const f=harness({timeout:()=>{entered.resolve();return pending.promise;},close:()=>pending.resolve()});
 const failed=assert.rejects(f.run(request(),{deadline:Date.now()+2000,signal:controller.signal}),failure);
 await entered.promise;
 controller.abort();
 await failed;
 await pending.promise;
 assert.deepEqual(f.events,['lease','connect','statement timeout','close']);
});
