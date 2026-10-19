import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readdir,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {build} from 'vite';
// @ts-expect-error Native Node source type erasure.
import {exercise} from './form-load-manual.test.ts';

test('actual Vite graph shares SDK across manual/automatic entries and excludes diagnostics from production',async t=>{
  const root=fileURLToPath(new URL('../..',import.meta.url)),directory=await mkdtemp(join(tmpdir(),'payment-manual-graph-'));
  try{
    await build({root,configFile:root+'/vite.config.ts',mode:'form-diagnostic',logLevel:'silent',build:{outDir:directory}});
    const assets=await readdir(join(directory,'assets')),manual=assets.find(name=>/^formManual-.*\.js$/.test(name)),automatic=assets.find(name=>/^formDiagnostic-.*\.js$/.test(name));
    assert.ok(manual);assert.ok(automatic);
    const manualCode=await readFile(join(directory,'assets',manual),'utf8'),automaticCode=await readFile(join(directory,'assets',automatic),'utf8');
    const shared=assets.filter(name=>name.endsWith('.js')&&manualCode.includes(name)&&automaticCode.includes(name));assert.ok(shared.length>0);
    for(const name of assets)assert.doesNotMatch(await readFile(join(directory,'assets',name),'utf8'),/node:http|node:crypto|createManualFormGateway/);
    for(const scenario of ['no-click','synchronous-ready','delayed-ready','frame-only','late-session'])await t.test(scenario,()=>exercise(pathToFileURL(join(directory,'assets',manual)).href,scenario,true));
    await build({root,configFile:root+'/vite.config.ts',mode:'production',logLevel:'silent',build:{outDir:directory,emptyOutDir:true}});
    const documents=await readdir(directory);assert.ok(documents.includes('index.html'));assert.ok(documents.includes('payment-return.html'));
    assert.equal(documents.includes('form-load-manual.html'),false);assert.equal(documents.includes('form-load-diagnostic.html'),false);
    for(const name of await readdir(join(directory,'assets')))assert.doesNotMatch(await readFile(join(directory,'assets',name),'utf8'),/manual-session|manual-observation|Ручная проверка/);
  }finally{await rm(directory,{recursive:true,force:true});}
});
