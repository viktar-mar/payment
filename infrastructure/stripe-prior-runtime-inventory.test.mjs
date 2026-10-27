import test from 'node:test';
import assert from 'node:assert/strict';
import {priorRuntimeInventory} from './stripe-prior-runtime-inventory.mjs';

const RUN='11111111-2222-4333-8444-555555555555';
const SOURCE='a'.repeat(40),DAEMON='unix:///tmp/synthetic-daemon.sock';
const ROLES=['issuer-installer','issuer-probe','postgres','mailpit','demo-site','callback-receiver'];
const ids=['1','2','3','4','5','6','7'].map(value=>value.repeat(64));
function fixture(status='STOPPED',backend=false) {
  const roles=[...ROLES,...(backend?['backend']:[])];
  return {
    metadata:{runId:RUN,expectedSource:SOURCE,daemon:DAEMON,project:'stripe-'+RUN,status,
      containers:Object.fromEntries(roles.map((role,index)=>[role,{id:ids[index],unverified:'PRIVATE_CANARY'}]))},
    expected:{runId:RUN,expectedSource:SOURCE,daemon:DAEMON,expectedMetadataStatus:status,backend},
  };
}
function refuses(metadata,expected) {
  assert.throws(()=>priorRuntimeInventory(metadata,expected),error=>{
    assert.equal(error.constructor,Error);
    assert.equal(error.message,'PRIOR_METADATA_UNCONFIRMED');
    assert.equal(error.cause,undefined);
    assert.equal(String(error).includes('PRIVATE_CANARY'),false);
    return true;
  });
}

// Break: preserving the old STOPPED-only predicate rejects an explicitly expected PENDING inventory.
for(const status of ['PENDING','STOPPED'])for(const backend of [false,true])test(`expected ${status} yields immutable ${backend?'seven':'six'}-role inventory only`,()=>{
  const {metadata,expected}=fixture(status,backend);
  const inventory=priorRuntimeInventory(metadata,expected);
  const wanted=[
    ...(backend?[{id:'7'.repeat(64),run:RUN,role:'backend'}]:[]),
    {id:'6'.repeat(64),run:RUN,role:'callback-receiver'},
    {id:'5'.repeat(64),run:RUN,role:'demo-site'},
    {id:'1'.repeat(64),run:RUN,role:'issuer-installer'},
    {id:'2'.repeat(64),run:RUN,role:'issuer-probe'},
    {id:'4'.repeat(64),run:RUN,role:'mailpit'},
    {id:'3'.repeat(64),run:RUN,role:'postgres'},
  ];
  assert.deepEqual(inventory,wanted);
  assert.equal(Object.isFrozen(inventory),true);
  for(const item of inventory){assert.equal(Object.isFrozen(item),true);assert.deepEqual(Object.keys(item),['id','run','role']);}
  assert.throws(()=>inventory.push({}),TypeError);
  assert.throws(()=>{inventory[0].id='f'.repeat(64);},TypeError);
  metadata.containers.postgres.id='f'.repeat(64);metadata.runId='PRIVATE_CANARY';
  assert.deepEqual(inventory,wanted);
});

// Break: globally allowing PENDING or accepting the expected value without checking it.
test('PENDING is refused when STOPPED is expected and STOPPED is refused when PENDING is expected',()=>{
  for(const [actual,wanted] of [['PENDING','STOPPED'],['STOPPED','PENDING']]){
    const {metadata,expected}=fixture(actual,true);expected.expectedMetadataStatus=wanted;refuses(metadata,expected);
  }
});
test('unknown or missing metadata states cannot be admitted even as exact expectations',()=>{
  for(const status of ['NEW','PREPARED','LIVE','READY','BUILD_ONLY_COMPLETE','pending','PRIVATE_CANARY','',null,undefined,0,{}]){
    const {metadata,expected}=fixture();metadata.status=status;expected.expectedMetadataStatus=status;refuses(metadata,expected);
  }
  const {metadata,expected}=fixture();delete metadata.status;delete expected.expectedMetadataStatus;refuses(metadata,expected);
});

// Break: matching fewer than all run/source/daemon/project identity fields, or loose equality.
test('run source daemon and stripe-run project must match exactly',()=>{
  for(const field of ['runId','expectedSource','daemon','project']){
    for(const replacement of ['PRIVATE_CANARY',undefined,null,{},0]){
      const {metadata,expected}=fixture('PENDING',true);metadata[field]=replacement;refuses(metadata,expected);
    }
  }
  for(const field of ['runId','expectedSource','daemon']){
    const {metadata,expected}=fixture();delete metadata[field];delete expected[field];refuses(metadata,expected);
  }
  for(const value of [null,undefined,[],false]){const {metadata,expected}=fixture();refuses(value,expected);refuses(metadata,value);}
});

// Break: role subsets/supersets, treating backend as truthy, or accepting non-record containers.
test('the role set is exactly six plus backend only for a boolean true expectation',()=>{
  for(const backend of [false,true])for(const mutation of ['missing','extra','wrong-role','array','null','string']){
    const {metadata,expected}=fixture('STOPPED',backend);
    if(mutation==='missing')delete metadata.containers.postgres;
    if(mutation==='extra')metadata.containers['PRIVATE_CANARY']={id:ids[0]};
    if(mutation==='wrong-role'){metadata.containers['callback_receiver']=metadata.containers['callback-receiver'];delete metadata.containers['callback-receiver'];}
    if(mutation==='array')metadata.containers=[];
    if(mutation==='null')metadata.containers=null;
    if(mutation==='string')metadata.containers='PRIVATE_CANARY';
    refuses(metadata,expected);
  }
  for(const backend of [false,true]){const {metadata,expected}=fixture('STOPPED',backend);expected.backend=!backend;refuses(metadata,expected);}
  for(const backend of [undefined,null,0,1,'true','false']){const {metadata,expected}=fixture();expected.backend=backend;refuses(metadata,expected);}
});

// Break: accepting clipped/coerced/uppercase IDs or missing container records.
test('each container has a full lowercase 64-hex ID',()=>{
  for(const id of ['a'.repeat(12),'a'.repeat(63),'a'.repeat(65),'A'.repeat(64),'g'.repeat(64),' '+ids[0],ids[0]+'\n',null,undefined,0,{},['a'.repeat(64)]]){
    const {metadata,expected}=fixture();metadata.containers.postgres.id=id;refuses(metadata,expected);
  }
  for(const value of [null,undefined,'PRIVATE_CANARY',[],{}]){const {metadata,expected}=fixture();metadata.containers.postgres=value;refuses(metadata,expected);}
});
