import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
import {Worker,isMainThread,parentPort,workerData} from 'node:worker_threads';
import {build} from 'vite';

if(isMainThread)test('actual diagnostic ESM build executes its singleton registry handshake in synthetic DOM only',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'payment-form-graph-'));
  try {
    const root=fileURLToPath(new URL('../..',import.meta.url));
    await build({root,configFile:root+'/vite.config.ts',mode:'form-diagnostic',logLevel:'silent',build:{outDir:directory}});
    const files=await readdir(join(directory,'assets'));
    const entry=files.find(name=>/^formDiagnostic-.*\.js$/.test(name));assert.ok(entry);
    const worker=new Worker(new URL(import.meta.url),{workerData:pathToFileURL(join(directory,'assets',entry)).href});
    const result=await new Promise<unknown>((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error('offline_graph_check_timeout')),10000);
      worker.once('message',value=>{clearTimeout(timer);resolve(value);});
      worker.once('error',()=>{clearTimeout(timer);reject(new Error('offline_graph_check_failed'));});
    }).finally(()=>worker.terminate());
    assert.deepEqual(result,{handshake:true,oneWidget:true,readonly:true,armed:true,onlyReaders:true,networkCalls:0});
  }finally {await rm(directory,{recursive:true,force:true});}
});
else if(!isMainThread){
  // A worker isolates the built page's deliberately non-configurable read-only
  // facade from the Node test host. jsdom is the existing SDK test dependency;
  // no real browser is launched, no resource loader/network is enabled.
  const sdkRequire=createRequire(new URL('../../../../frontend/sdk/package.json',import.meta.url));
  const {JSDOM}=sdkRequire('jsdom') as {JSDOM:new(html:string,options:{url:string})=>{window:Window&typeof globalThis}};
  const dom=new JSDOM('<main id="diagnostic"></main>',{url:'http://127.0.0.1:14173/form-load-diagnostic.html'});
  const window=dom.window;
  for(const name of ['window','document','HTMLElement','customElements','sessionStorage','location','navigator','CustomEvent','MutationObserver','getComputedStyle'] as const)
    Object.defineProperty(globalThis,name,{value:name==='window'?window:window[name],configurable:true});
  Object.defineProperty(globalThis,'__formDiagnosticSession',{value:{apiBaseUrl:'http://127.0.0.1:18080',siteKey:'test-site',priceKey:'test-price'}});
  let networkCalls=0;globalThis.fetch=async()=>{networkCalls++;throw new Error('offline_network_forbidden');};
  try {
    await import(workerData as string);
    const descriptor=Object.getOwnPropertyDescriptor(globalThis,'__formLoadDiagnostic');
    const api=descriptor?.value as {handshake:()=>boolean;snapshot:()=>{binding:string}}|undefined;
    parentPort?.postMessage({handshake:api?.handshake()===true,oneWidget:window.document.querySelectorAll('generic-payment-widget').length===1,
      readonly:descriptor?.writable===false&&descriptor.configurable===false&&Object.isFrozen(api),armed:api?.snapshot().binding==='ARMED',
      onlyReaders:!!api&&Object.keys(api).sort().join(',')==='handshake,snapshot',networkCalls});
  }finally {window.close();}
}
