import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,readdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {build} from 'vite';
import {EventEmitter} from 'node:events';
import {createManualPaymentGateway} from '../../../../infrastructure/stripe-manual-payment-gateway.mjs';
// @ts-expect-error Native Node source type erasure.
import {exercise} from './manual-payment.test.ts';
const root=fileURLToPath(new URL('../..',import.meta.url));
test('actual manual payment graph emits only its page and owned manifest; old builds exclude payment authority',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'manual-payment-graph-'));
  try{
    await build({root,configFile:root+'/vite.config.ts',mode:'manual-payment',logLevel:'silent',build:{outDir:directory}});
    assert.deepEqual((await readdir(directory)).sort(),['.vite','assets','manual-payment.html']);
    const manifest=JSON.parse(await readFile(join(directory,'.vite/manifest.json'),'utf8'));assert.equal(manifest['manual-payment.html'].isEntry,true);
    const server=new EventEmitter() as EventEmitter&{listen(options:unknown):void;close(callback:()=>void):void};
    server.listen=()=>{queueMicrotask(()=>server.emit('listening'));};server.close=callback=>{queueMicrotask(callback);};
    const checkoutId='11111111-1111-4111-8111-111111111111',expiresAt='2099-01-01T00:00:00Z';
    const gateway=createManualPaymentGateway({origin:'http://127.0.0.1:18083',assetEndpoint:'http://127.0.0.1:18081',authorizeConfirmation:async()=>true,
      session:{version:2,apiBaseUrl:'http://127.0.0.1:18080',siteKey:'test-site',priceKey:'test-price',checkoutId,checkoutToken:'x'.repeat(43),expiresAt,providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state:{checkoutId,paymentId:'22222222-2222-4222-8222-222222222222',amountMinor:2000,currency:'EUR',status:'REQUIRES_PAYMENT_METHOD',expiresAt,price:{priceId:'33333333-3333-4333-8333-333333333333',priceKey:'test-price',version:1,amountMinor:2000,currency:'EUR',taxMode:'NONE'}}}},
      {deadline:Date.now()+2700000},{fetch:async(input)=>{assert.equal(input,'http://127.0.0.1:18081/.vite/manifest.json');return Response.json(manifest);},createServer:()=>server as never});
    assert.deepEqual(await gateway.open(),{url:'http://127.0.0.1:18083/manual-payment.html'});assert.equal(await gateway.close({deadline:Date.now()+1000}),true);
    const entry=pathToFileURL(join(directory,manifest['manual-payment.html'].file)).href;await t.test('real emitted page',()=>exercise(entry,'success',true));
    for(const scenario of ['session-forbidden','session-json-invalid','widget-configuration','mount-append'])await t.test('real emitted page '+scenario,()=>exercise(entry,scenario,true));
    for(const mode of ['production','form-diagnostic']){
      await build({root,configFile:root+'/vite.config.ts',mode,logLevel:'silent',build:{outDir:directory,emptyOutDir:true}});
      assert.equal((await readdir(directory)).includes('manual-payment.html'),false);
      for(const name of await readdir(join(directory,'assets')))assert.doesNotMatch(await readFile(join(directory,'assets',name),'utf8'),/manual-payment-session|manual-payment-confirmation|Один тестовый платёж/);
    }
  }finally{await rm(directory,{recursive:true,force:true});}
});
