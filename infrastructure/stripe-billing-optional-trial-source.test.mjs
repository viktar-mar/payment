import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {mkdir,mkdtemp,rm,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {dirname,join} from 'node:path';
import test from 'node:test';
import {promisify} from 'node:util';
import {hasOnlyTrackedOptionalTrialInputs} from './stripe-billing-optional-trial-source.mjs';

test('ignore rules cannot hide Docker build inputs and exclusions are anchored to the SDK root',async t=>{
  const cwd=await mkdtemp(join(tmpdir(),'optional-trial-git-'));
  t.after(()=>rm(cwd,{recursive:true,force:true}));
  const execute=promisify(execFile);
  const settings={cwd,timeout:10000,maxBuffer:16384,env:{PATH:'/usr/bin:/bin',LANG:'C',TMPDIR:cwd,
    GIT_CONFIG_GLOBAL:'/dev/null',GIT_CONFIG_SYSTEM:'/dev/null',GIT_OPTIONAL_LOCKS:'0'}};
  const git=(...args)=>execute('/usr/bin/git',args,settings);
  await git('init','--quiet');
  await writeFile(join(cwd,'.git/info/exclude'),'*\n');
  const create=async path=>{
    const file=join(cwd,path);
    await mkdir(dirname(file),{recursive:true});
    await writeFile(file,'local synthetic input');
    return file;
  };
  await create('frontend/sdk/node_modules/fixture/index.js');
  await create('frontend/sdk/dist/fixture/index.js');
  await create('infrastructure/test-data-ignored/private-fixture');
  assert.equal(await hasOnlyTrackedOptionalTrialInputs(execute,settings),true);
  for(const path of [
    'backend/src/main/resources/META-INF/ignored.class',
    'examples/demo-site/src/ignored.ts',
    'frontend/sdk/src/dist/ignored.ts',
    'frontend/sdk/src/node_modules/fixture/index.ts',
    'examples/demo-site/Dockerfile.dockerignore',
    'examples/demo-site/dockerfile.dockerignore',
    'backend/Dockerfile.billing-optional-trial.dockerignore',
  ]){
    const file=await create(path);
    assert.equal((await git('status','--porcelain=v1','--untracked-files=all')).stdout,'');
    assert.equal(await hasOnlyTrackedOptionalTrialInputs(execute,settings),false,path);
    await rm(file);
  }
  assert.equal(await hasOnlyTrackedOptionalTrialInputs(execute,settings),true);
});
