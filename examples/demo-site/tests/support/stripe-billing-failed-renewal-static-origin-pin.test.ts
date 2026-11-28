import assert from 'node:assert/strict';
import {createHmac, randomBytes} from 'node:crypto';
import {chmod, link, mkdir, mkdtemp, readFile, realpath, rename, rm, symlink, unlink, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {dirname, join} from 'node:path';
import test, {type TestContext} from 'node:test';
import {pathToFileURL} from 'node:url';

const origin = 'https://assets.example.invalid';

async function fixture(t:TestContext) {
  // Exercise the deployed loader in an isolated checkout with an unrelated test pin.
  const root = await realpath(await mkdtemp(join(tmpdir(), 'static-origin-pin-')));
  t.after(()=>rm(root,{recursive:true,force:true}));
  const key = randomBytes(32);
  const pin = createHmac('sha256',key).update(origin).digest('hex');
  const source = await readFile(new URL('./stripe-billing-failed-renewal-static-origin-pin.ts',import.meta.url),'utf8');
  const declaration = /const REVIEWED_HMAC_SHA256 = '[0-9a-f]{64}';/g;
  assert.equal(source.match(declaration)?.length,1);
  const module = join(root,'examples/demo-site/tests/support/stripe-billing-failed-renewal-static-origin-pin.ts');
  await mkdir(dirname(module),{recursive:true});
  await writeFile(module,source.replace(declaration,"const REVIEWED_HMAC_SHA256 = '"+pin+"';"));
  const directory = join(root,'.superpowers/failed-renewal-static-origin');
  await mkdir(directory,{recursive:true,mode:0o700});
  const file=join(directory,'policy.key');
  await writeFile(file,key,{flag:'wx',mode:0o600});
  const {loadFailedRenewalStaticOriginMatcher:load} = await import(pathToFileURL(module).href);
  return {load:load as ()=>((candidate:string)=>boolean),root,directory,file};
}

test('a provisioned key matches exactly the reviewed canonical origin',async t=>{
  const {load}=await fixture(t);
  const matches=load();
  assert.equal(matches(origin),true);
  for(const other of [origin+'/',origin+':443',origin+'?q=x',origin+'#part',origin+'.',origin+'.example',
    'https://user@assets.example.invalid','https://child.assets.example.invalid','https://ASSETS.example.invalid',
    'http://assets.example.invalid','https://'+ 'x'.repeat(300),'not a URL']){
    assert.equal(matches(other),false);
  }
});

for(const fault of ['absent','unrelated key','short key','oversized key','file permissions','directory permissions',
  'key symlink','key hardlink','directory symlink','ancestor symlink']) {
  test('the local static-origin approval fails closed for '+fault,async t=>{
    const {load,root,directory,file}=await fixture(t);
    const elsewhere=join(root,'elsewhere');
    switch(fault){
      case 'absent': await unlink(file); break;
      case 'unrelated key': await writeFile(file,randomBytes(32)); break;
      case 'short key': await writeFile(file,randomBytes(31)); break;
      case 'oversized key': await writeFile(file,randomBytes(33)); break;
      case 'file permissions': await chmod(file,0o640); break;
      case 'directory permissions': await chmod(directory,0o750); break;
      case 'key symlink': await rename(file,elsewhere); await symlink(elsewhere,file); break;
      case 'key hardlink': await link(file,elsewhere); break;
      case 'directory symlink': await rename(directory,elsewhere); await symlink(elsewhere,directory); break;
      case 'ancestor symlink': await rename(dirname(directory),elsewhere); await symlink(elsewhere,dirname(directory)); break;
    }
    assert.equal(load()(origin),false);
  });
}
