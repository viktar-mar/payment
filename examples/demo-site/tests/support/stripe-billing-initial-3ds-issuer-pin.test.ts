import test from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {chmodSync,mkdirSync,mkdtempSync,realpathSync,rmSync,symlinkSync,writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
// @ts-ignore Node runs the same private loader as the Playwright worker.
import {readPinnedInitial3dsIssuerOrigin} from './stripe-billing-initial-3ds-issuer-pin.ts';

const runId='11111111-2222-4333-8444-555555555555',source='a'.repeat(40);
const origin='https://unlisted-fixture.stripe.com';
const original={runId,expectedSource:source,origin,checkpoint:'BROWSER_PROVIDER_ORIGIN_STRIPE_COM',issuerRelation:'ISSUER_TRANSACTION_MATCH'};

for(const fault of ['none','stale source','foreign run','unverified frame','altered report','shared file','shared parent','symlink','wrong key']){
 test('private issuer pin requires the exact reviewed report and file identity: '+fault,()=>{
  const directory=mkdtempSync(join(realpathSync(tmpdir()),'billing-issuer-pin-'));
  try{
   const evidence=join(directory,'evidence'),policy=join(directory,'policy');
   mkdirSync(evidence,{mode:0o700});mkdirSync(policy,{mode:0o700});
   const key=Buffer.alloc(32,7),value={...original};
   if(fault==='stale source')value.expectedSource='b'.repeat(40);
   if(fault==='foreign run')value.runId='11111111-2222-4333-8444-555555555556';
   if(fault==='unverified frame')value.issuerRelation='ISSUER_TRANSACTION_MATCH_FRAME_UNVERIFIED';
   const bytes=Buffer.from(JSON.stringify(value)),report=join(evidence,'refused-provider-origin.private.json');
   const review={runId,source,hmac:createHmac('sha256',key).update(bytes).digest('hex')};
   if(fault==='symlink'){
    const copy=join(evidence,'other.json');writeFileSync(copy,bytes,{mode:0o600});symlinkSync(copy,report);
   }else writeFileSync(report,fault==='altered report'?JSON.stringify({...value,origin:'https://forged-fixture.stripe.com'}):bytes,{mode:0o600});
   if(fault==='shared file')chmodSync(report,0o640);
   if(fault==='shared parent')chmodSync(evidence,0o750);
   writeFileSync(join(policy,'policy.key'),fault==='wrong key'?Buffer.alloc(32,8):key,{mode:0o600});
   assert.equal(readPinnedInitial3dsIssuerOrigin(evidence,policy,review),fault==='none'?origin:undefined);
  }finally{rmSync(directory,{recursive:true});}
 });
}
