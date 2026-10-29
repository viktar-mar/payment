import test from 'node:test';
import assert from 'node:assert/strict';
import {Readable} from 'node:stream';
import {EventEmitter} from 'node:events';
import {createHmac,createHash} from 'node:crypto';
import {createReceiver} from './callback-receiver.mjs';
test('signed receiver exposes exact body digest separately from unchanged default inspection',async()=>{
 let handle;createReceiver({}, {createServer:fn=>{handle=fn;return new EventEmitter();}});assert.equal(typeof handle,'function');
 const secret=Buffer.alloc(32,7),id='11111111-2222-4333-8444-555555555555';
 async function request(url,body,headers={}){let result;const req=Readable.from(body?[Buffer.from(body)]:[]);Object.assign(req,{url,method:url.startsWith('/inspection')?'GET':'POST',headers:{'content-type':'application/json',...headers},rawHeaders:[]});await handle(req,{headersSent:false,writeHead(status){result={status};},end(text){if(text)result.value=JSON.parse(text);}});return result;}
 assert.equal((await request('/configure',JSON.stringify({secret:secret.toString('base64url'),secret_version:1}))).status,204);
 const payload={schema_version:1,event_id:id,event_type:'payment.succeeded',site_id:id,payment_id:id,external_order_id:null,payment_status:'SUCCEEDED',amount_minor:2000,currency:'EUR',occurred_at:new Date().toISOString()};
 const body=JSON.stringify(payload),timestamp=String(Math.floor(Date.now()/1000)),headers={'payment-timestamp':timestamp,'payment-event-id':id,'payment-secret-version':'1','payment-signature':'v1='+createHmac('sha256',secret).update(timestamp+'.'+body).digest('hex')};
 assert.equal((await request('/callback',body,headers)).status,204);
 assert.deepEqual((await request('/inspection')).value,{count:1,duplicates:0,events:[{event_id:id,event_type:'payment.succeeded',count:1}]});
 assert.deepEqual((await request('/inspection/digests')).value,{count:1,duplicates:0,events:[{event_id:id,event_type:'payment.succeeded',count:1,sha256:createHash('sha256').update(body).digest('hex')}]});
 assert.equal((await request('/callback',body,{...headers,'payment-signature':'v1='+'0'.repeat(64)})).status,401);
 assert.equal((await request('/inspection/digests')).value.count,1);
 await request('/callback',body,headers);assert.equal((await request('/inspection/digests')).value.duplicates,1);
});
