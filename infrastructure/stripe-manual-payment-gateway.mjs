import {createServer} from 'node:http';
import {randomBytes} from 'node:crypto';
const failure=()=>new Error('stripe_manual_payment_GATEWAY_UNCONFIRMED');
const DOCUMENT='/manual-payment.html',RETURN='/payment-return.html';
const RETURN_HTML='<!doctype html><html lang="ru"><head><meta name="referrer" content="no-referrer"><script>history.replaceState(null,\'\',location.pathname)</script><meta charset="utf-8"><title>Stripe Sandbox</title></head><body><h1>Stripe Sandbox</h1><p>Браузер вернулся из формы. Окончательный результат проверяет оператор по серверному отчёту. Закройте эту вкладку. Повторная оплата недоступна.</p></body></html>';
const SECURITY=Object.freeze({
  'Content-Security-Policy':"default-src 'self'; connect-src 'self' https://api.stripe.com http://127.0.0.1:* http://localhost:*; img-src 'self' data:; style-src 'self' 'sha256-HOUlC+CJIYCGelbn67Ej16SQ0d752rAxnOlfB0Orrvo=' 'sha256-eD+GA3VtZu+RW+18cTefkFWG73vBFDvmsj67/CZYZMs=' 'sha256-zFr4CzDBlu/8ka0jjBKoY+7ZI/oygj9ZXLFCwRgSsjU=' 'sha256-oAhG5DyJ/Yyj2xFFcUwFqljsA2kWYh02LqDFizj2plA='; script-src 'self' https://js.stripe.com https://*.js.stripe.com 'sha256-UBodp8UhLR9ASBc1PNRKcs3r2CjAFWxCbrUGPN7u1jo='; frame-src 'self' https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  'Referrer-Policy':'no-referrer','X-Content-Type-Options':'nosniff','X-Frame-Options':'DENY','Permissions-Policy':'camera=(), microphone=(), geolocation=()','Cache-Control':'no-store',
});
function exact(value,keys){
  if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw failure();
  const descriptors=Object.getOwnPropertyDescriptors(value);if(Reflect.ownKeys(descriptors).length!==keys.length)throw failure();
  return Object.fromEntries(keys.map(key=>{const d=descriptors[key];if(!d||!d.enumerable||!Object.hasOwn(d,'value'))throw failure();return [key,d.value];}));
}
function endpoint(value){
  if(typeof value!=='string'||!/^http:\/\/127\.0\.0\.1:[1-9][0-9]{3,4}$/.test(value))throw failure();
  const url=new URL(value),port=Number(url.port);if(port<1024||port>65535||url.origin!==value)throw failure();return port;
}
function snapshot(value){
  const s=exact(value,['version','apiBaseUrl','siteKey','priceKey','checkoutId','checkoutToken','expiresAt','providerType','confirmationMode','state']);
  const state=exact(s.state,['checkoutId','paymentId','amountMinor','currency','price','status','expiresAt']),price=exact(state.price,['priceId','priceKey','version','amountMinor','currency','taxMode']);
  const uuid=value=>typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value),key=value=>typeof value==='string'&&/^[a-z0-9][a-z0-9-]{2,62}$/.test(value);
  endpoint(s.apiBaseUrl);
  if(s.version!==2||s.providerType!=='STRIPE'||s.confirmationMode!=='CLIENT_PROVIDER'||!key(s.siteKey)||!key(s.priceKey)||!uuid(s.checkoutId)
    ||typeof s.checkoutToken!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(s.checkoutToken)||typeof s.expiresAt!=='string'
    ||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(s.expiresAt)||!Number.isFinite(Date.parse(s.expiresAt))
    ||new Date(s.expiresAt).toISOString().slice(0,19)!==s.expiresAt.slice(0,19)||state.checkoutId!==s.checkoutId||!uuid(state.paymentId)
    ||state.amountMinor!==2000||state.currency!=='EUR'||state.status!=='REQUIRES_PAYMENT_METHOD'||state.expiresAt!==s.expiresAt
    ||!uuid(price.priceId)||price.priceKey!==s.priceKey||price.version!==1||price.amountMinor!==2000||price.currency!=='EUR'||price.taxMode!=='NONE')throw failure();
  s.state={...state,price};const wire=JSON.stringify(s);if(Buffer.byteLength(wire)>8192)throw failure();return wire;
}
function manifestAssets(wire){
  const manifest=JSON.parse(wire);if(!manifest||typeof manifest!=='object'||Array.isArray(manifest)||Object.keys(manifest).length>128)throw failure();
  const assets=new Set(),seen=new Set();
  function path(value,extension){if(typeof value!=='string'||!new RegExp('^assets/[A-Za-z0-9_-]+\\.'+extension+'$').test(value))throw failure();assets.add('/'+value);}
  function visit(key){
    if(typeof key!=='string'||!Object.hasOwn(manifest,key))throw failure();if(seen.has(key))return;
    const record=manifest[key];if(!record||typeof record!=='object'||Array.isArray(record)||Object.keys(record).some(k=>!['file','name','src','isEntry','isDynamicEntry','imports','dynamicImports','css','assets'].includes(k)))throw failure();
    if(key==='manual-payment.html'?record.isEntry!==true:record.isEntry===true)throw failure();
    path(record.file,'js');
    for(const field of ['imports','dynamicImports','css','assets'])if(record[field]!==undefined&&(!Array.isArray(record[field])||record[field].length>128))throw failure();
    if(record.assets?.length)throw failure();for(const css of record.css??[])path(css,'css');
    // Vite's lazy Stripe loader imports its entry chunk: valid graph cycles
    // must terminate traversal without treating manifest keys as fetch paths.
    seen.add(key);for(const child of [...record.imports??[],...record.dynamicImports??[]])visit(child);
  }
  visit('manual-payment.html');if(seen.size!==Object.keys(manifest).length)throw failure();return assets;
}
/** Fixed local handoff. A grant records permission only, never payment success. */
export function createManualPaymentGateway(options,limits,dependencies={}){
  let origin,assetEndpoint,session,authorizeConfirmation,deadline,signal,port,expiresAt;
  try{
    ({origin,assetEndpoint,session,authorizeConfirmation}=exact(options,['origin','assetEndpoint','session','authorizeConfirmation']));port=endpoint(origin);endpoint(assetEndpoint);session=snapshot(session);
    const parsed=JSON.parse(session);expiresAt=Date.parse(parsed.expiresAt);if(origin===assetEndpoint||origin===parsed.apiBaseUrl||assetEndpoint===parsed.apiBaseUrl||typeof authorizeConfirmation!=='function')throw failure();
    const keys=Reflect.ownKeys(Object.getOwnPropertyDescriptors(limits??{}));if(!keys.includes('deadline')||keys.some(key=>!['deadline','signal'].includes(key)))throw failure();({deadline,signal}=exact(limits,keys));
    if(!Number.isSafeInteger(deadline)||deadline<=0||(signal!==undefined&&!(signal instanceof AbortSignal)))throw failure();
  }catch{throw failure();}
  let io,server,used=false,stopped=false,closing=false,documentUsed=false,sessionUsed=false,permitUsed=false,cookie,sessionResponse,sessionDelivered=false;
  let listenPending=false,listenSettled=false,closeRequested=false,serverClosed=false,uncertain=false,timer,cutoff=deadline-390000,allowed=new Set();
  let resolveWait,rejectOpen,cleanupPromise;const waiting=new Promise(resolve=>{resolveWait=resolve;});
  const tasks=new Set(),requests=new Set(),responses=new Set(),sockets=new Set(),controllers=new Set(),changes=new Set();
  const changed=()=>{for(const callback of changes)callback();};
  function track(promise){tasks.add(promise);promise.then(()=>{tasks.delete(promise);changed();},()=>{tasks.delete(promise);changed();});return promise;}
  function stop(outcome){
    if(stopped)return;stopped=true;session=null;cookie=null;io?.clearTimeout(timer);signal?.removeEventListener('abort',abort);
    for(const controller of controllers)controller.abort();rejectOpen?.(failure());resolveWait(Object.freeze({outcome}));changed();
  }
  const abort=()=>stop('FAILED');
  function check(){if(stopped||closing)return false;if(signal?.aborted||!Number.isFinite(io.now())||io.now()>=deadline){stop('FAILED');return false;}if(io.now()>=cutoff){stop(permitUsed?'FAILED':'NOT_STARTED');return false;}return true;}
  function arm(){io.clearTimeout(timer);timer=io.setTimeout(()=>check(),Math.max(1,cutoff-io.now()));}
  function closeListener(){if(!server||!listenSettled||closeRequested)return;closeRequested=true;try{server.close(error=>{if(!error){serverClosed=true;changed();}});}catch{uncertain=true;}}
  function dispose(){closeListener();for(const controller of controllers)controller.abort();for(const owned of [...requests,...responses,...sockets])try{owned.destroy();}catch{uncertain=true;}}
  function reply(res,status,body='',type='text/plain; charset=utf-8'){if(res.destroyed||res.writableFinished)return;res.statusCode=status;res.setHeader('Content-Type',type);res.end(body);}
  function header(req,key){const raw=req.rawHeaders;if(!Array.isArray(raw)||raw.length%2)return null;let count=0;for(let n=0;n<raw.length;n+=2)if(typeof raw[n]==='string'&&raw[n].toLowerCase()===key)count++;const value=req.headers?.[key];return count===1&&typeof value==='string'?value:null;}
  function authenticated(req){const raw=header(req,'cookie');return header(req,'origin')===origin&&cookie&&raw===`payment_manual_payment=${cookie}`;}
  async function bounded(operation,budget=io.now()+10000){
    const controller=new AbortController();controllers.add(controller);const expiry=Math.min(budget,cutoff,deadline);let timeout,onAbort;
    const work=track(Promise.resolve().then(()=>{if(!check()||controller.signal.aborted||io.now()>=expiry)throw failure();return operation(controller.signal,expiry);}));
    try{
      const cancellation=new Promise((_,reject)=>{onAbort=()=>reject(failure());controller.signal.addEventListener('abort',onAbort,{once:true});timeout=io.setTimeout(()=>controller.abort(),Math.max(1,expiry-io.now()));if(controller.signal.aborted)onAbort();});
      const result=await Promise.race([work,cancellation]);if(!check()||controller.signal.aborted||io.now()>=expiry)throw failure();return result;
    }finally{io.clearTimeout(timeout);controller.signal.removeEventListener('abort',onAbort);controllers.delete(controller);}
  }
  async function upstream(path,kind,maximum,httpDeadline=io.now()+10000){
    return bounded(async(signal,expiry)=>{
      const response=await io.fetch(assetEndpoint+path,{method:'GET',redirect:'error',credentials:'omit',signal});
      const discard=async()=>{if(response.body)try{await response.body.cancel();}catch{uncertain=true;throw failure();}};
      if(!check()||signal.aborted||io.now()>=expiry){await discard();throw failure();}
      const type=response.headers.get('content-type')?.split(';')[0].trim().toLowerCase(),expected=kind==='html'?['text/html']:kind==='json'?['application/json']:kind==='css'?['text/css']:['text/javascript','application/javascript'];
      const length=response.headers.get('content-length');if(response.status!==200||response.redirected||!expected.includes(type)||length!==null&&(!/^\d+$/.test(length)||Number(length)>maximum)||!response.body){await discard();throw failure();}
      const reader=response.body.getReader(),chunks=[];let bytes=0,cancelled;
      const cancel=()=>{cancelled??=track(Promise.resolve().then(()=>reader.cancel()).catch(()=>{uncertain=true;throw failure();}));cancelled.catch(()=>{});return cancelled;};
      signal.addEventListener('abort',cancel,{once:true});
      try{while(true){if(!check()||signal.aborted||io.now()>=expiry)throw failure();const part=await reader.read();if(!check()||signal.aborted||io.now()>=expiry)throw failure();if(part.done)break;bytes+=part.value.byteLength;if(bytes>maximum)throw failure();chunks.push(Buffer.from(part.value));}return {body:Buffer.concat(chunks),type};}
      catch{await cancel();throw failure();}finally{signal.removeEventListener('abort',cancel);reader.releaseLock();}
    },httpDeadline);
  }
  async function emptyBody(req,httpDeadline){
    const length=header(req,'content-length');if(length!==null&&(!/^\d+$/.test(length)||Number(length)>32))throw 413;
    if(header(req,'content-type')!=='application/json'||req.headers?.['content-encoding']!==undefined)throw 400;
    return bounded(async signal=>{const cancel=()=>req.destroy();signal.addEventListener('abort',cancel,{once:true});
      try{const chunks=[];let bytes=0;for await(const chunk of req){const data=Buffer.from(chunk);bytes+=data.byteLength;if(bytes>32)throw 413;chunks.push(data);}
        const body=new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(chunks));if(!/^\s*\{\s*\}\s*$/.test(body))throw 400;
      }finally{signal.removeEventListener('abort',cancel);}
    },httpDeadline);
  }
  async function handle(req,res){
    // Strip private return query before any other request processing. No request logging.
    if(typeof req.url==='string'&&req.url.split(/[?#]/,1)[0]===RETURN){req.url=RETURN;if('originalUrl'in req)req.originalUrl=RETURN;}
    const httpDeadline=Math.min(io.now()+10000,cutoff,deadline);
    const httpTimer=io.setTimeout(()=>{stop('FAILED');for(const owned of [req,res])try{owned.destroy();}catch{uncertain=true;}},Math.max(1,httpDeadline-io.now()));
    requests.add(req);responses.add(res);const settled=()=>{io.clearTimeout(httpTimer);responses.delete(res);res.removeListener('finish',finish);res.removeListener('close',closed);changed();};
    const finish=()=>{if(sessionResponse===res){sessionDelivered=true;sessionResponse=null;}settled();};
    const closed=()=>{if(!res.writableFinished)stop('FAILED');settled();};res.once('finish',finish);res.once('close',closed);res.once('error',()=>{stop('FAILED');try{res.destroy();}catch{uncertain=true;}});
    for(const [key,value]of Object.entries(SECURITY))res.setHeader(key,value);
    try{
      if(header(req,'host')!==origin.slice(7)){reply(res,403);return;}
      const path=req.url,post=path==='/manual-payment-session'||path==='/manual-payment-confirmation';
      if(![DOCUMENT,RETURN].includes(path)&&!post&&!allowed.has(path)){reply(res,404);return;}
      if(req.method!==(post?'POST':'GET')){reply(res,405);return;}
      // Fixed return has no session authority and remains safe after the sole grant.
      if(path===RETURN){reply(res,200,RETURN_HTML,'text/html; charset=utf-8');return;}
      if(!check()){reply(res,410);return;}
      if(path===DOCUMENT){
        if(documentUsed){reply(res,410);return;}documentUsed=true;
        const content=await upstream(path,'html',2097152,httpDeadline);if(!check()||io.now()>=httpDeadline)throw failure();const entropy=io.randomBytes(32);if(!Buffer.isBuffer(entropy)||entropy.byteLength!==32)throw failure();cookie=entropy.toString('hex');
        res.setHeader('Set-Cookie',`payment_manual_payment=${cookie}; HttpOnly; SameSite=Strict; Path=/`);reply(res,200,content.body,content.type+'; charset=utf-8');return;
      }
      if(allowed.has(path)){const content=await upstream(path,path.endsWith('.css')?'css':'js',2097152,httpDeadline);if(!check()||io.now()>=httpDeadline)throw failure();reply(res,200,content.body,content.type+'; charset=utf-8');return;}
      if(!authenticated(req)){reply(res,403);return;}
      if(path==='/manual-payment-session'){
        if(sessionUsed){reply(res,410);return;}sessionUsed=true;
        if(io.now()+390000>deadline||io.now()>=expiresAt){stop('NOT_STARTED');reply(res,410);return;}
        cutoff=Math.min(io.now()+300000,expiresAt,deadline-90000);arm();const wire=session;session=null;
        await emptyBody(req,httpDeadline);if(!check()||io.now()>=httpDeadline){reply(res,410);return;}sessionResponse=res;reply(res,200,wire,'application/json; charset=utf-8');return;
      }
      if(!sessionDelivered){reply(res,409);return;}if(permitUsed){reply(res,410);return;}
      // Reserve irreversibly before body await; durable callback commits before reply.
      permitUsed=true;await emptyBody(req,httpDeadline);if(!check()||io.now()>=httpDeadline){reply(res,410);return;}
      const permitted=await bounded(()=>authorizeConfirmation(),httpDeadline);if(permitted!==true||!check()||io.now()>=httpDeadline)throw failure();
      reply(res,200,'{"authorized":true}','application/json; charset=utf-8');stop('CONFIRMATION_GRANTED');
    }catch(error){stop('FAILED');reply(res,error===413?413:error===400?400:502);}finally{requests.delete(req);changed();}
  }
  return Object.freeze({
    async open(){
      if(used)throw failure();used=true;
      try{
        const now=dependencies.now??Date.now,current=now();if(signal?.aborted||!Number.isFinite(current)||deadline>current+2700000||current+390000>deadline||expiresAt<=current)throw failure();
        io={now,fetch:dependencies.fetch??globalThis.fetch,createServer:dependencies.createServer??createServer,randomBytes:dependencies.randomBytes??randomBytes,setTimeout:dependencies.setTimeout??setTimeout,clearTimeout:dependencies.clearTimeout??clearTimeout};
        signal?.addEventListener('abort',abort,{once:true});arm();allowed=manifestAssets((await upstream('/.vite/manifest.json','json',65536)).body.toString('utf8'));if(!check())throw failure();
        server=io.createServer((req,res)=>{track(handle(req,res)).catch(()=>stop('FAILED'));});server.on('connection',socket=>{sockets.add(socket);socket.once('close',()=>{sockets.delete(socket);changed();});if(closing||stopped)socket.destroy();});
        server.on('error',()=>{listenPending=false;listenSettled=true;stop('FAILED');closeListener();changed();});
        const opened=new Promise((resolve,reject)=>{rejectOpen=reject;server.once('listening',()=>{listenPending=false;listenSettled=true;if(closing||!check()){reject(failure());closeListener();}else resolve(Object.freeze({url:origin+DOCUMENT}));changed();});});opened.catch(()=>{});
        listenPending=true;server.listen({host:'127.0.0.1',port,exclusive:true});return await bounded(()=>opened);
      }catch{stop('FAILED');throw failure();}
    },
    async wait(){if(!used)throw failure();if(io)check();return waiting;},
    async close(cleanupLimits){
      if(cleanupPromise)return cleanupPromise;if(!io){used=true;closing=true;stop('NOT_STARTED');return true;}
      const cleanupDeadline=cleanupLimits?.deadline,cleanupSignal=cleanupLimits?.signal;
      if(!Number.isSafeInteger(cleanupDeadline)||cleanupDeadline>deadline+120000||cleanupDeadline<=io.now()||(cleanupSignal!==undefined&&!(cleanupSignal instanceof AbortSignal))||cleanupSignal?.aborted){closing=true;stop('FAILED');dispose();return false;}
      closing=true;stop(permitUsed?'FAILED':'NOT_STARTED');let resolveCleanup;cleanupPromise=new Promise(resolve=>{resolveCleanup=resolve;});let finished=false,cleanupTimer;
      const complete=value=>{if(finished)return;finished=true;io.clearTimeout(cleanupTimer);cleanupSignal?.removeEventListener('abort',cancel);changes.delete(prove);resolveCleanup(value);};
      const prove=()=>{if(io.now()>=cleanupDeadline||cleanupSignal?.aborted){complete(false);return;}if(!uncertain&&(!server||serverClosed)&&!listenPending&&!tasks.size&&!requests.size&&!responses.size&&!sockets.size)complete(true);};
      const cancel=()=>complete(false);changes.add(prove);cleanupSignal?.addEventListener('abort',cancel,{once:true});cleanupTimer=io.setTimeout(()=>complete(false),Math.max(1,cleanupDeadline-io.now()));dispose();prove();return cleanupPromise;
    },
  });
}
