import {createServer as httpServer} from 'node:http';
import {randomBytes as secureRandomBytes} from 'node:crypto';
import {validateFormDiagnosticSample,classifyFormDiagnosticSample} from './stripe-form-diagnostic-schema.mjs';

const failure=()=>new Error('stripe_manual_form_GATEWAY_UNCONFIRMED');
const DOCUMENT='/form-load-manual.html';
// Kept identical to examples/demo-site/nginx.conf; upstream cannot weaken policy.
const SECURITY=Object.freeze({
  'Content-Security-Policy':"default-src 'self'; connect-src 'self' https://api.stripe.com http://127.0.0.1:* http://localhost:*; img-src 'self' data:; style-src 'self' 'sha256-HOUlC+CJIYCGelbn67Ej16SQ0d752rAxnOlfB0Orrvo=' 'sha256-eD+GA3VtZu+RW+18cTefkFWG73vBFDvmsj67/CZYZMs=' 'sha256-zFr4CzDBlu/8ka0jjBKoY+7ZI/oygj9ZXLFCwRgSsjU=' 'sha256-oAhG5DyJ/Yyj2xFFcUwFqljsA2kWYh02LqDFizj2plA='; script-src 'self' https://js.stripe.com https://*.js.stripe.com 'sha256-UBodp8UhLR9ASBc1PNRKcs3r2CjAFWxCbrUGPN7u1jo='; frame-src 'self' https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  'Referrer-Policy':'no-referrer','X-Content-Type-Options':'nosniff','X-Frame-Options':'DENY',
  'Permissions-Policy':'camera=(), microphone=(), geolocation=()','Cache-Control':'no-store',
});
const SESSION_KEYS=['version','apiBaseUrl','siteKey','priceKey','checkoutId','checkoutToken','expiresAt','providerType','confirmationMode','state'];
function exact(value,keys) {
  if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw failure();
  const descriptors=Object.getOwnPropertyDescriptors(value);
  if(Reflect.ownKeys(descriptors).length!==keys.length)throw failure();
  return Object.fromEntries(keys.map(key=>{const d=descriptors[key];if(!d||!d.enumerable||!Object.hasOwn(d,'value'))throw failure();return [key,d.value];}));
}
function endpoint(value) {
  if(typeof value!=='string'||!/^http:\/\/127\.0\.0\.1:[1-9][0-9]{3,4}$/.test(value))throw failure();
  const url=new URL(value),port=Number(url.port);
  if(port<1024||port>65535||url.origin!==value)throw failure();return port;
}
function sessionSnapshot(value) {
  const s=exact(value,SESSION_KEYS),state=exact(s.state,['checkoutId','paymentId','amountMinor','currency','price','status','expiresAt']);
  const price=exact(state.price,['priceId','priceKey','version','amountMinor','currency','taxMode']);
  const uuid=value=>typeof value==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);
  const key=value=>typeof value==='string'&&/^[a-z0-9][a-z0-9-]{2,62}$/.test(value);
  endpoint(s.apiBaseUrl);
  if(s.version!==2||s.providerType!=='STRIPE'||s.confirmationMode!=='CLIENT_PROVIDER'||!key(s.siteKey)||!key(s.priceKey)
    ||!uuid(s.checkoutId)||typeof s.checkoutToken!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(s.checkoutToken)
    ||typeof s.expiresAt!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(s.expiresAt)
    ||!Number.isFinite(Date.parse(s.expiresAt))||new Date(s.expiresAt).toISOString().slice(0,19)!==s.expiresAt.slice(0,19)
    ||state.checkoutId!==s.checkoutId||!uuid(state.paymentId)||state.amountMinor!==2000||state.currency!=='EUR'
    ||state.status!=='REQUIRES_PAYMENT_METHOD'||state.expiresAt!==s.expiresAt||!uuid(price.priceId)||price.priceKey!==s.priceKey
    ||price.version!==1||price.amountMinor!==2000||price.currency!=='EUR'||price.taxMode!=='NONE')throw failure();
  s.state=Object.freeze({...state,price:Object.freeze(price)});Object.freeze(s);
  const wire=JSON.stringify(s);if(Buffer.byteLength(wire)>8192)throw failure();return wire;
}

/** Constructor validates/snapshots data only; network, randomness and clocks start in open. */
export function createManualFormGateway(options,limits,dependencies={}) {
  let origin,assetEndpoint,session,deadline,signal,port;
  try {
    ({origin,assetEndpoint,session}=exact(options,['origin','assetEndpoint','session']));port=endpoint(origin);endpoint(assetEndpoint);
    session=sessionSnapshot(session);const parsed=JSON.parse(session);
    if(origin===assetEndpoint||origin===parsed.apiBaseUrl||assetEndpoint===parsed.apiBaseUrl)throw failure();
    const keys=Reflect.ownKeys(Object.getOwnPropertyDescriptors(limits??{}));
    if(keys.some(key=>!['deadline','signal'].includes(key))||!keys.includes('deadline'))throw failure();
    ({deadline,signal}=exact(limits,keys));
    if(!Number.isSafeInteger(deadline)||deadline<=0||(signal!==undefined&&!(signal instanceof AbortSignal)))throw failure();
  }catch{throw failure();}
  let server,used=false,io,closing=false,stopped=false,documentUsed=false,sessionUsed=false,cookie=null,uncertain=false;
  let listenPending=false,listenSettled=false,serverClosed=false,closeRequested=false,timer,cutoff=deadline-120000;
  let observation='NOT_STARTED',end='NOT_STARTED',sample=null,pendingUsed=false,terminalUsed=false;
  let sessionResponse=null,sessionDelivered=false,reportReservations=0;
  let resolveWait,resolveOpen,rejectOpen,cleanupTimer,cleanupAbort,cleanupPromise,cleanupResolve;
  const resultPromise=new Promise(resolve=>{resolveWait=resolve;});
  const tasks=new Set(),responses=new Set(),requests=new Set(),sockets=new Set(),controllers=new Set();
  const cleanupChanges=new Set();
  function changed(){for(const notify of cleanupChanges)notify();}
  function tracked(promise){tasks.add(promise);promise.then(()=>{tasks.delete(promise);changed();},()=>{tasks.delete(promise);changed();});return promise;}
  function view(){return Object.freeze({observation,end,sample});}
  function stop(cause) {
    if(stopped)return;stopped=true;end=cause;session=null;cookie=null;sessionResponse=null;
    if(observation==='NOT_STARTED'&&sessionUsed)observation='NO_REPORT';
    if(io)io.clearTimeout(timer);signal?.removeEventListener('abort',onAbort);
    for(const controller of controllers)controller.abort();
    rejectOpen?.(failure());resolveWait(view());changed();
  }
  function onAbort(){stop('ABORTED');}
  function check() {
    if(stopped||closing)return false;
    if(signal?.aborted){stop('ABORTED');return false;}
    if(io.now()>=deadline){stop('ACTIVE_DEADLINE');return false;}
    if(io.now()>=cutoff){stop(sessionUsed?(observation==='READY_REPORTED'?'READY_REPORTED':'LOCAL_LIMIT'):'NOT_STARTED');return false;}
    return true;
  }
  function arm(){io.clearTimeout(timer);timer=io.setTimeout(()=>check(),Math.max(1,cutoff-io.now()));}
  function closeListener() {
    if(!server||!listenSettled||closeRequested)return;
    closeRequested=true;
    try{server.close(error=>{if(!error){serverClosed=true;changed();}});}catch{}
  }
  function ownResponse(req,res) {
    responses.add(res);requests.add(req);
    const settled=()=>{responses.delete(res);res.removeListener('finish',completed);res.removeListener('close',interrupted);changed();};
    const completed=()=>{if(sessionResponse===res){sessionDelivered=true;sessionResponse=null;}settled();};
    const interrupted=()=>{if(!res.writableFinished)stop('FAILED');settled();};
    res.once('finish',completed);res.once('close',interrupted);
    res.once('error',()=>{stop('FAILED');try{res.destroy();}catch{uncertain=true;}});
    for(const [key,value]of Object.entries(SECURITY))res.setHeader(key,value);
  }
  function disposeOwned() {
    closeListener();
    for(const controller of controllers)controller.abort();
    for(const req of requests)try{req.destroy();}catch{uncertain=true;}
    for(const res of responses)try{res.destroy();}catch{uncertain=true;}
    for(const socket of sockets)try{socket.destroy();}catch{uncertain=true;}
  }
  function reply(res,status,body='',type='text/plain; charset=utf-8') {
    if(res.destroyed||res.writableFinished)return;
    res.statusCode=status;res.setHeader('Content-Type',type);res.end(body);
  }
  function singleHeader(req,key) {
    const raw=req.rawHeaders;
    if(!Array.isArray(raw)||raw.length%2!==0)return null;
    let count=0;for(let n=0;n<raw.length;n+=2)if(typeof raw[n]==='string'&&raw[n].toLowerCase()===key)count++;
    const value=req.headers?.[key];return count===1&&typeof value==='string'?value:null;
  }
  function authenticated(req) {
    if(singleHeader(req,'origin')!==origin)return false;
    const raw=singleHeader(req,'cookie');if(!raw||!cookie||raw.length>4096)return false;
    const matches=raw.split(';').map(value=>value.trim()).filter(value=>value.startsWith('payment_manual_form='));
    return matches.length===1&&matches[0]==='payment_manual_form='+cookie;
  }
  async function bounded(body,budget=cutoff) {
    const controller=new AbortController();controllers.add(controller);
    const operationDeadline=Math.min(budget,cutoff,deadline);
    let timeout,abort;
    const operation=tracked(Promise.resolve().then(()=>{
      if(!check()||controller.signal.aborted||io.now()>=operationDeadline)throw failure();return body(controller.signal);
    }));
    try {
      const cancelled=new Promise((_,reject)=>{
        abort=()=>reject(failure());controller.signal.addEventListener('abort',abort,{once:true});
        timeout=io.setTimeout(()=>controller.abort(),Math.max(1,operationDeadline-io.now()));
        if(controller.signal.aborted)abort();
      });
      const value=await Promise.race([operation,cancelled]);if(!check()||controller.signal.aborted||io.now()>=operationDeadline)throw failure();return value;
    }finally{io.clearTimeout(timeout);controller.signal.removeEventListener('abort',abort);controllers.delete(controller);}
  }
  async function readBody(req,maximum) {
    const length=singleHeader(req,'content-length');
    if(length!==null&&(!/^[0-9]+$/.test(length)||Number(length)>maximum))throw 413;
    if(singleHeader(req,'content-type')!=='application/json'||req.headers?.['content-encoding']!==undefined)throw 400;
    return bounded(async()=>{
      const chunks=[];let bytes=0;
      for await(const chunk of req){if(!check())throw failure();const data=Buffer.from(chunk);bytes+=data.byteLength;if(bytes>maximum)throw 413;chunks.push(data);}
      if(!check())throw failure();return new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(chunks));
    });
  }
  async function asset(path,res,document) {
    const expiry=Math.min(cutoff,deadline,io.now()+10000);
    try {
      const content=await bounded(async signal=>{
        const upstream=await io.fetch(assetEndpoint+path,{method:'GET',redirect:'error',credentials:'omit',signal});
        // Keep body disposal owned even when headers arrive after cancellation.
        const discard=async()=>{if(upstream.body)try{await upstream.body.cancel();}catch{uncertain=true;throw failure();}};
        if(!check()||signal.aborted||io.now()>=expiry){await discard();throw failure();}
        const type=upstream.headers.get('content-type')?.split(';')[0].trim().toLowerCase();
        const expected=document?['text/html']:path.endsWith('.css')?['text/css']:['text/javascript','application/javascript'];
        if(upstream.status!==200||upstream.redirected||!expected.includes(type)){
          await discard();throw failure();
        }
        const length=upstream.headers.get('content-length');
        if(length!==null&&(!/^[0-9]+$/.test(length)||Number(length)>2097152)){
          await discard();throw failure();
        }
        if(!upstream.body)throw failure();
        const reader=upstream.body.getReader();const chunks=[];let bytes=0,cancellation;
        const cancel=()=>{cancellation??=tracked(Promise.resolve().then(()=>reader.cancel()).catch(()=>{uncertain=true;throw failure();}));cancellation.catch(()=>{});return cancellation;};
        signal.addEventListener('abort',cancel,{once:true});
        try {
          while(true){if(!check()||signal.aborted||io.now()>=expiry)throw failure();const part=await reader.read();
            if(!check()||signal.aborted||io.now()>=expiry)throw failure();if(part.done)break;
            bytes+=part.value.byteLength;if(bytes>2097152)throw failure();chunks.push(Buffer.from(part.value));}
          return {body:Buffer.concat(chunks),type};
        }catch{await cancel();throw failure();}finally{signal.removeEventListener('abort',cancel);reader.releaseLock();}
      },expiry);
      if(!check())throw failure();
      if(document){
        const random=io.randomBytes(32);if(!Buffer.isBuffer(random)||random.byteLength!==32)throw failure();
        cookie=random.toString('hex');res.setHeader('Set-Cookie','payment_manual_form='+cookie+'; HttpOnly; SameSite=Strict; Path=/');
      }
      reply(res,200,content.body,content.type+'; charset=utf-8');
    }catch{stop('FAILED');reply(res,502);}
  }
  async function handle(req,res) {
    ownResponse(req,res);
    try {
      if(!check()){reply(res,410);return;}
      if(singleHeader(req,'host')!==origin.slice(7)){reply(res,403);return;}
      const path=req.url;
      const isAsset=typeof path==='string'&&/^\/assets\/[A-Za-z0-9_-]+\.(?:js|css)$/.test(path);
      if(![DOCUMENT,'/manual-session','/manual-observation','/favicon.ico'].includes(path)&&!isAsset){reply(res,404);return;}
      const post=path==='/manual-session'||path==='/manual-observation';
      if(req.method!==(post?'POST':'GET')){reply(res,405);return;}
      if(path==='/favicon.ico'){reply(res,204);return;}
      if(path===DOCUMENT){if(documentUsed){reply(res,410);return;}documentUsed=true;await asset(path,res,true);return;}
      if(isAsset){await asset(path,res,false);return;}
      if(!authenticated(req)){reply(res,403);return;}
      if(path==='/manual-session'){
        if(sessionUsed){reply(res,410);return;}
        // Consume authenticated exchange authority before the first body await.
        sessionUsed=true;observation='NO_REPORT';end='LOCAL_LIMIT';cutoff=Math.min(io.now()+60000,deadline-60000);arm();
        const wire=session;session=null;
        try {
          const body=await readBody(req,32),value=JSON.parse(body);
          if(!value||Array.isArray(value)||typeof value!=='object'||Object.keys(value).length||!/^\s*\{\s*\}\s*$/.test(body))throw 400;
          if(!check()){reply(res,410);return;}
          sessionResponse=res;reply(res,200,wire,'application/json; charset=utf-8');
        }catch(error){if(check())stop('FAILED');reply(res,error===413?413:400);}return;
      }
      if(!sessionDelivered){reply(res,409);return;}
      if(terminalUsed){reply(res,410);return;}
      if(reportReservations>=2){reply(res,410);return;}reportReservations++;
      let body,value,accepted=false;
      try {
        body=await readBody(req,4096);value=validateFormDiagnosticSample(JSON.parse(body));
        if(!value||(body.match(/:/g)??[]).length!==Object.keys(value).length)throw 400;
        const classification=classifyFormDiagnosticSample(value);
        const isReady=classification.startsWith('FORM_READY_')&&value.bucket!=='AT_LIMIT';
        const isPending=classification.startsWith('WAITING_')||['ELEMENT_SETUP_INCOMPLETE','WIDGET_HANDOFF_INCOMPLETE'].includes(classification);
        const isTerminal=['ELEMENT_LOADERROR','OPERATION_FAILED','ABORTED','BINDING_LOST','CONFIRMATION_BLOCKED'].includes(classification);
        if(!isReady&&!isPending&&!isTerminal)throw 400;
        if(!check()||terminalUsed){reply(res,410);return;}
        if(isPending&&value.bucket!=='AT_LIMIT'){
          if(value.bucket!=='AFTER_15S')throw 400;
          if(pendingUsed){reply(res,410);return;}pendingUsed=true;accepted=true;sample=value;observation='PENDING_REPORTED';reply(res,204);return;
        }
        terminalUsed=true;accepted=true;sample=value;
        if(isReady){observation='READY_REPORTED';end='READY_REPORTED';reply(res,204);return;}
        observation=isTerminal?'TERMINAL_REPORTED':'PENDING_REPORTED';reply(res,204);stop(isTerminal?'FAILED':'LOCAL_LIMIT');
      }catch(error){
        if(!check()){reply(res,410);return;}
        // An already admitted malformed body cannot replace historical ready.
        if(!terminalUsed){observation='INVALID_REPORT';sample=null;}
        stop('FAILED');reply(res,error===413?413:400);
      }finally{if(!accepted)reportReservations--;}
    }catch{stop('FAILED');reply(res,500);}finally{requests.delete(req);changed();}
  }
  return Object.freeze({
    async open(){
      if(used)throw failure();used=true;
      try {
        const now=dependencies.now??Date.now,current=now();
        if(signal?.aborted||deadline>current+900000||current>=cutoff||Date.parse(JSON.parse(session).expiresAt)<=current)throw failure();
        io={now,setTimeout:dependencies.setTimeout??setTimeout,clearTimeout:dependencies.clearTimeout??clearTimeout,
          fetch:dependencies.fetch??globalThis.fetch,randomBytes:dependencies.randomBytes??secureRandomBytes,createServer:dependencies.createServer??httpServer};
        if(deadline>io.now()+900000||Date.parse(JSON.parse(session).expiresAt)<=io.now()||!check())throw failure();
        signal?.addEventListener('abort',onAbort,{once:true});arm();
        server=io.createServer((req,res)=>{tracked(handle(req,res)).catch(()=>stop('FAILED'));});
        server.on('connection',socket=>{
          sockets.add(socket);socket.once('close',()=>{sockets.delete(socket);changed();});if(closing||stopped)socket.destroy();
        });
        server.on('error',()=>{listenPending=false;listenSettled=true;stop('FAILED');closeListener();changed();});
        const opened=new Promise((resolve,reject)=>{resolveOpen=resolve;rejectOpen=reject;});
        // Synchronous listen refusal can reject before open reaches its await.
        opened.catch(()=>{});
        server.once('listening',()=>{
          listenPending=false;listenSettled=true;
          if(closing||!check()){rejectOpen(failure());closeListener();}else resolveOpen(Object.freeze({url:origin+DOCUMENT}));changed();
        });
        listenPending=true;server.listen({host:'127.0.0.1',port,exclusive:true});return await opened;
      }catch{stop('FAILED');throw failure();}
    },
    async wait(){if(!used)throw failure();if(io)check();return resultPromise;},
    async close(cleanupLimits){
      if(cleanupPromise)return cleanupPromise;
      if(!io){if(!used){used=true;closing=true;stop('ABORTED');cleanupPromise=Promise.resolve(true);return cleanupPromise;}return false;}
      const cleanupDeadline=cleanupLimits?.deadline,cleanupSignal=cleanupLimits?.signal;
      if(!Number.isSafeInteger(cleanupDeadline)||cleanupDeadline>deadline+120000||cleanupDeadline<=io.now()
        ||(cleanupSignal!==undefined&&!(cleanupSignal instanceof AbortSignal))||cleanupSignal?.aborted){closing=true;stop('FAILED');disposeOwned();return false;}
      closing=true;stop('ABORTED');
      cleanupPromise=new Promise(resolve=>{cleanupResolve=resolve;});
      let cleanupFinished=false;
      const complete=value=>{if(cleanupFinished)return;cleanupFinished=true;io.clearTimeout(cleanupTimer);cleanupSignal?.removeEventListener('abort',cleanupAbort);cleanupChanges.delete(prove);cleanupResolve(value);};
      const prove=()=>{if(io.now()>=cleanupDeadline||cleanupSignal?.aborted){complete(false);return;}
        if(!uncertain&&(!server||serverClosed)&&!listenPending&&tasks.size===0&&responses.size===0&&requests.size===0&&sockets.size===0)complete(true);};
      cleanupChanges.add(prove);cleanupAbort=()=>complete(false);cleanupSignal?.addEventListener('abort',cleanupAbort,{once:true});
      cleanupTimer=io.setTimeout(()=>complete(false),Math.max(1,cleanupDeadline-io.now()));
      disposeOwned();
      prove();return cleanupPromise;
    },
  });
}
