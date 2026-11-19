import assert from 'node:assert/strict';
import {mkdtemp,readdir,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import test from 'node:test';
import {build} from 'vite';

// Node records the script that the installed loader would insert. Its DOM
// double does not download or execute scripts.
async function checkFirstScript(outDir:string, html:string) {
  const entry = /<script\b[^>]*\bsrc="(\/assets\/[\w.-]+\.js)"/.exec(html)?.[1];
  assert.ok(entry, 'dedicated page entry');
  const scripts:string[] = [];
  const host = {isConnected:true, style:{}};
  const status = {textContent:'IDLE'};
  const window = {
    addEventListener() {}, removeEventListener() {},
    Stripe: undefined as unknown,
    configureStripeBrowserLoadCheck: undefined as unknown as (key:string)=>Promise<void>
  };
  const document = {
    head: {appendChild(script:{src:string}) {scripts.push(script.src);}},
    getElementById: (id:string)=>id==='provider-host'?host:status,
    querySelectorAll: ()=>[],
    querySelector: ()=>null,
    createElement(tag:string) {
      if(tag==='link') return {relList:{supports:()=>true}};
      assert.equal(tag, 'script');
      return {addEventListener(event:string, listener:()=>void) {
        if(event==='load') queueMicrotask(()=>{
          window.Stripe = Object.assign(()=>null, {version:'endive'});
          listener();
        });
      }};
    }
  };
  const globals={window,document};
  for(const name of Object.keys(globals)) assert.ok(!Object.hasOwn(globalThis,name));
  Object.assign(globalThis,globals);
  try {
    await import(pathToFileURL(join(outDir,entry)).href);
    assert.deepEqual(scripts, [], 'page import must not load Stripe');
    await window.configureStripeBrowserLoadCheck('pk_test_artificialOnly');
    assert.equal(scripts.length,1);
    const url=new URL(scripts[0]!);
    assert.equal(url.origin,'https://js.stripe.com');
    assert.deepEqual([...url.searchParams], [['advancedFraudSignals','false']]);
  } finally {
    for(const name of Object.keys(globals)) Reflect.deleteProperty(globalThis,name);
  }
}

test('dedicated build uses shared loader only and default delivery excludes probe',async()=>{
  const root=fileURLToPath(new URL('../..',import.meta.url));
  const directory=await mkdtemp(join(tmpdir(),'stripe-load-build-'));
  try{
    for(const mode of ['stripe-browser-load-check','production']){
      const modules=new Set<string>();const outDir=join(directory,mode);
      await build({root,configFile:join(root,'vite.config.ts'),configLoader:'runner',envFile:false,mode,logLevel:'silent',cacheDir:join(directory,'cache'),build:{outDir},plugins:[{name:'inspect-load-only',generateBundle(_options,bundle){for(const output of Object.values(bundle))if(output.type==='chunk')for(const id of output.moduleIds)modules.add(id);}}]});
      const files=await readdir(outDir);
      if(mode==='production'){
        assert.ok(!files.includes('stripe-browser-load-check.html'));assert.ok(![...modules].some(id=>id.endsWith('/stripe-browser-load-check.ts')));
      }else{
        assert.deepEqual(files.sort(),['assets','stripe-browser-load-check.html']);
        assert.ok([...modules].some(id=>id.endsWith('/frontend/sdk/src/providers/stripe-loader.ts')));
        assert.ok([...modules].some(id=>id.includes('/@stripe/stripe-js/')&&id.endsWith('/pure/index.js')),'real installed Stripe pure loader is bundled');
        assert.ok(![...modules].some(id=>/\/(?:client|runtime-factory|stripe-billing-failed-renewal|stripe-billing-first-payment)\.[mt]?[js]+$/.test(id)));
        const html=await readFile(join(outDir,'stripe-browser-load-check.html'),'utf8');
        assert.ok(!/<(?:form|input|button)\b/i.test(html));assert.match(html,/\binert\b/);assert.match(html,/tabindex="-1"/);
        await checkFirstScript(outDir,html);
      }
    }
  }finally{await rm(directory,{recursive:true,force:true});}
});
