import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {dirname,join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {build} from 'vite';

test('dedicated billing builds configure the manager before Pay and guarded builds avoid a favicon request',async()=>{
 const root=fileURLToPath(new URL('../..',import.meta.url)),temp=await mkdtemp(join(tmpdir(),'decline-retry-demo-'));
 try{
  for(const mode of ['billing-decline-retry','billing-initial-3ds','billing-invoice-webhooks','production']){
   const outDir=join(temp,mode);
   const sdkDir=dirname(fileURLToPath(import.meta.resolve('@generic-payments/sdk')));
   const loader=join(sdkDir,'providers','stripe-loader.js'),modules=new Set<string>();
   let probe='';
   await build({root,mode,envFile:false,configFile:join(root,'vite.config.ts'),configLoader:'runner',logLevel:'silent',
    cacheDir:join(temp,'cache'),build:{outDir},plugins:[{
     name:'probe-the-shipped-sdk-loader',
     buildStart(){this.emitFile({type:'chunk',id:loader,name:'loader-probe',preserveSignature:'strict'});},
     generateBundle(_options,bundle){for(const entry of Object.values(bundle))if(entry.type==='chunk'){
      for(const id of entry.moduleIds)modules.add(id);
      if(entry.facadeModuleId===loader)probe=entry.fileName;
     }}
    }]});
   assert.ok(probe);
   assert.ok([...modules].some(id=>id.endsWith('/subscriptions/generic-subscription-manager.js')));
   assert.equal([...modules].filter(id=>id.includes('/@stripe/stripe-js/')&&id.endsWith('/pure/index.js')).length,1);
   const html=await readFile(join(outDir,'index.html'),'utf8'),entry=/<script\b[^>]*src="\/(assets\/[\w.-]+\.js)"/.exec(html)?.[1];
   assert.ok(entry);
   assert.equal(/<link rel="icon" href="data:,">/.test(html),['billing-initial-3ds','billing-invoice-webhooks'].includes(mode));
   const scripts:string[]=[];
   const document={
    querySelector:()=>null,querySelectorAll:()=>[],
    createElement:(tag:string)=>tag==='link'?{relList:{supports:()=>true}}:{addEventListener(event:string,fn:()=>void){
     if(event==='load')queueMicrotask(()=>{window.Stripe=Object.assign(()=>null,{version:'endive'});fn();});}},
    head:{appendChild:(script:{src:string})=>{scripts.push(script.src);}},
   };
   const window={Stripe:undefined as unknown};
   const globals={document,window,HTMLElement:class{}};
   for(const key of Object.keys(globals))assert.equal(Object.hasOwn(globalThis,key),false);
   Object.assign(globalThis,globals);
   try{
    await import(pathToFileURL(join(outDir,entry)).href);
    assert.deepEqual(scripts,[]);
    const {loadStripeClient}=await import(pathToFileURL(join(outDir,probe)).href);
    await loadStripeClient('pk_test_artificialOnly');
    assert.equal(scripts.length,1);const requested=new URL(scripts[0]!);
    assert.equal(requested.origin,'https://js.stripe.com');
    assert.deepEqual([...requested.searchParams],mode==='production'?[]:[['advancedFraudSignals','false']]);
   }finally{for(const key of Object.keys(globals))Reflect.deleteProperty(globalThis,key);}
  }
 }finally{await rm(temp,{recursive:true,force:true});}
});
