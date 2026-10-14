import http from 'node:http';
import { createHmac, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const TYPES = new Set(['payment.requires_payment_method','payment.requires_action','payment.processing','payment.succeeded','payment.failed','payment.cancelled','refund.processing','refund.succeeded','refund.failed']);
const FIELDS = ['amount_minor','currency','event_id','event_type','external_order_id','occurred_at','payment_id','payment_status','schema_version','site_id'];
const DONATION_FIELDS = [...FIELDS,'donation_id','campaign_key','customer_id'].sort();
const SUBSCRIPTION_FIELDS = [...FIELDS,'subscription_id','billing_record_id','customer_id','product_id','billing_kind','price_id','price_version','period_start','period_end'].sort();
const LIFECYCLE_FIELDS = ['schema_version','event_id','event_type','site_id','subscription_id','customer_id','product_id','revision','state','access_allowed','access_valid_until','occurred_at','billing_record_id','payment_id'].sort();
const validId = id => typeof id==='string'&&UUID.test(id);
const validTime = value => typeof value==='string'&&value.length<=64&&Number.isFinite(Date.parse(value));
function validLifecycle(p) {
  return Object.keys(p).sort().join(',')===LIFECYCLE_FIELDS.join(',')&&[p.event_id,p.site_id,p.subscription_id,p.customer_id,p.product_id].every(validId)
    &&[p.billing_record_id,p.payment_id].every(id=>id===null||validId(id))&&['subscription.updated','subscription.access_changed'].includes(p.event_type)
    &&Number.isSafeInteger(p.revision)&&p.revision>=0&&['INCOMPLETE','TRIALING','ACTIVE','PAST_DUE','PAUSED','UNPAID','CANCELLED','CANCEL_AT_PERIOD_END'].includes(p.state)
    &&typeof p.access_allowed==='boolean'&&validTime(p.occurred_at)
    &&(p.access_allowed?validTime(p.access_valid_until)&&Date.parse(p.access_valid_until)>Date.parse(p.occurred_at)&&Date.parse(p.access_valid_until)<=Date.parse(p.occurred_at)+300000:p.access_valid_until===null);
}
const STATUSES = new Set(['REQUIRES_PAYMENT_METHOD','REQUIRES_ACTION','PROCESSING','SUCCEEDED','FAILED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED']);
const json = (res,status,value) => { res.writeHead(status,{'content-type':'application/json','cache-control':'no-store'}); res.end(value === undefined ? undefined : JSON.stringify(value)); };

/** Test-consumer cache. Signed callbacks only invalidate; a bounded Partner read supplies authority.
 * Billing timestamps use the injected owned business clock. A separate real TTL bounds even a frozen test clock.
 * Revisions are per Subscription, not globally comparable across replacement subscriptions for the same Product.
 */
export function createSubscriptionAccessCache({identity,refresh,now=Date.now,realNow=Date.now}) {
  if(!identity||!['site_id','customer_id','product_id'].every(k=>validId(identity[k]))||typeof refresh!=='function')throw new Error('invalid_access_configuration');
  const owner=Object.freeze({...identity}),revisions=new Map();
  let decision=null,realExpiry=0,generation=0,refreshes=0,invalidations=0;
  const owned=p=>['site_id','customer_id','product_id'].every(k=>p?.[k]===owner[k]);
  const fresh=()=>decision?.allowed===true&&now()<Date.parse(decision.valid_until)&&realNow()<realExpiry;
  function inspect(){return {allowed:fresh(),revision:decision?.revision??null,refreshes,invalidations};}
  function observe(p) {
    if(p?.schema_version!==4||!owned(p)||!validId(p.subscription_id)||!Number.isSafeInteger(p.revision)||p.revision<0)return;
    if(p.revision<=(revisions.get(p.subscription_id)??-1))return;
    if(revisions.size>=10000&&!revisions.has(p.subscription_id))throw new Error('access_capacity');
    revisions.set(p.subscription_id,p.revision);generation++;invalidations++;decision=null;realExpiry=0;
  }
  async function read() {
    if(fresh())return inspect();
    decision=null;realExpiry=0;const started=generation;const controller=new AbortController();let timer;
    try {
      const result=await Promise.race([Promise.resolve().then(()=>refresh(controller.signal)),new Promise((_,reject)=>{timer=setTimeout(()=>{controller.abort();reject(new Error('access_refresh_timeout'));},5000);})]);
      const evaluated=Date.parse(result?.evaluated_at),until=Date.parse(result?.valid_until),business=now();
      // The authoritative current-Product query returns no Subscription after terminal release.
      // This is a denial, never a nullable grant or a reset of an old Subscription's revision fence.
      const absent=result?.reason==='NO_SUBSCRIPTION'&&result.subscription_id===null&&result.allowed===false
        &&result.revision===0&&result.entitled_price_id===null&&result.entitled_price_version===null&&result.valid_until===null;
      if(started!==generation||!owned(result)||(!absent&&!validId(result.subscription_id))||!Number.isSafeInteger(result.revision)
          ||result.revision<(revisions.get(result.subscription_id)??0)||typeof result.allowed!=='boolean'
          ||!Number.isFinite(evaluated)||evaluated>business||business-evaluated>300000
          ||(result.allowed?(!Number.isFinite(until)||until<=business||until>evaluated+300000):result.valid_until!==null))throw new Error('invalid_access_refresh');
      if(!absent)revisions.set(result.subscription_id,result.revision);
      decision=Object.freeze({...result});refreshes++;
      realExpiry=realNow()+Math.max(0,Math.min(300000,until-business));return inspect();
    } finally {clearTimeout(timer);controller.abort();}
  }
  return Object.freeze({observe,read,inspect});
}

/** Explicit owned test runtime only. Neither URLs nor credentials come from incoming requests. */
export function createOwnedAccessRuntime({backendUrl,clockUrl,capability}) {
  const backend=new URL(backendUrl),clock=new URL(clockUrl);
  const local=url=>url.protocol==='http:'&&['127.0.0.1','localhost','backend'].includes(url.hostname)&&!url.username&&!url.password&&!url.search&&!url.hash;
  if(!local(backend)||backend.pathname!=='/'||!local(clock)||clock.hostname!==backend.hostname||clock.pathname!=='/time'||!/^[A-Za-z0-9_-]{43}$/.test(capability??''))throw new Error('invalid_owned_runtime');
  let cache,business=0;
  async function bounded(url,authorization,limit,signal) {
    const response=await fetch(url,{headers:{authorization},redirect:'error',signal:signal?AbortSignal.any([signal,AbortSignal.timeout(5000)]):AbortSignal.timeout(5000)});
    if(!response.ok){await response.body?.cancel();throw new Error('owned_read_failed');}
    let size=0;const chunks=[];
    for await(const chunk of response.body){size+=chunk.length;if(size>limit)throw new Error('owned_read_too_large');chunks.push(chunk);}
    return new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(chunks));
  }
  return Object.freeze({
    configure(value) {
      if(!value||Object.keys(value).sort().join(',')!=='api_key,customer_id,product_id,site_id'||!['site_id','customer_id','product_id'].every(k=>validId(value[k]))||typeof value.api_key!=='string'||value.api_key.length<16||value.api_key.length>512||/[\s\u0000-\u001f\u007f]/.test(value.api_key))throw new Error('invalid_access_configuration');
      const {api_key,...identity}=value;
      cache=createSubscriptionAccessCache({identity,now:()=>business,refresh:async signal=>JSON.parse(await bounded(new URL(`/api/v1/partner/customers/${identity.customer_id}/products/${identity.product_id}/access`,backend),'Bearer '+api_key,65536,signal))});
    },
    observe(value){cache?.observe(value);},
    async read(){
      if(!cache)return null;
      const owned=cache,value=await bounded(clock,'Bearer '+capability,64);
      if(!validTime(value)||Date.parse(value)<business)throw new Error('invalid_business_time');
      business=Date.parse(value);
      const result=await owned.read();
      if(owned!==cache)throw new Error('stale_access_configuration');
      return result;
    }
  });
}

/** Internal local-development receiver: never retains bodies, secrets in diagnostics, or provider data. */
export function createReceiver({accessCache,accessRuntime}={}) {
  let key, version;
  const events=new Map(); let duplicates=0;
  const server=http.createServer(async (req,res) => {
    const route=req.url;
    if(!['/configure','/callback','/inspection','/health',...(accessRuntime?['/access','/access/configure']:[])].includes(route)) return json(res,404,{error:'not_found'});
    const expected=route==='/configure'||route==='/callback'||route==='/access/configure'?'POST':'GET';
    if(req.method!==expected) return json(res,405,{error:'method_not_allowed'});
    if(route==='/health') return json(res,200,{status:'UP'});
    if(route==='/inspection') return json(res,200,{count:events.size,duplicates,events:[...events.values()]});
    if(route==='/access') {
      try {const result=await accessRuntime.read();return json(res,result?200:409,result??{error:'access_not_configured'});}
      catch {return json(res,503,{error:'access_unavailable'});}
    }
    if(req.headers['content-type']!=='application/json') return json(res,415,{error:'unsupported_media'});
    // Duplicate security headers and compressed bodies must not be interpreted ambiguously.
    const security=['content-type','payment-event-id','payment-timestamp','payment-secret-version','payment-signature'];
    for(const name of security) if(req.rawHeaders.filter((_,i)=>i%2===0&&req.rawHeaders[i].toLowerCase()===name).length>1) return json(res,400,{error:'invalid_request'});
    if(req.headers['content-encoding']) return json(res,415,{error:'unsupported_encoding'});
    let bytes=0; const chunks=[];
    try {
      for await(const chunk of req) {bytes+=chunk.length;if(bytes>16384){req.resume();return json(res,413,{error:'body_too_large'});}chunks.push(chunk);}
      const body=Buffer.concat(chunks);
      // Fatal UTF-8 decoding rejects replacement characters introduced by malformed transport bytes.
      const text=new TextDecoder('utf-8',{fatal:true}).decode(body);
      const p=JSON.parse(text);
      if(!p||typeof p!=='object'||Array.isArray(p)) return json(res,400,{error:'invalid_request'});
      if(route==='/access/configure'){accessRuntime.configure(p);return json(res,204);}
      if(route==='/configure') {
        if(Object.keys(p).sort().join(',')!=='secret,secret_version'||!Number.isSafeInteger(p.secret_version)||p.secret_version<1||p.secret_version>2147483647||typeof p.secret!=='string'||!p.secret.match(/^[A-Za-z0-9_-]{43}$/)) return json(res,400,{error:'invalid_configuration'});
        const next=Buffer.from(p.secret,'base64url');
        if(next.length!==32||next.toString('base64url')!==p.secret) return json(res,400,{error:'invalid_configuration'});
        key?.fill(0);key=next;version=String(p.secret_version);return json(res,204);
      }
      const timestamp=req.headers['payment-timestamp'];const signature=req.headers['payment-signature'];
      if(!key||typeof timestamp!=='string'||!timestamp.match(/^[1-9][0-9]{0,10}$/)||!Number.isSafeInteger(Number(timestamp))||Math.abs(Math.floor(Date.now()/1000)-Number(timestamp))>300||req.headers['payment-secret-version']!==version||typeof signature!=='string'||!signature.match(/^v1=[0-9a-f]{64}$/)) return json(res,401,{error:'invalid_signature'});
      const calculated=createHmac('sha256',key).update(timestamp+'.').update(body).digest();
      const supplied=Buffer.from(signature.slice(3),'hex');
      if(supplied.length!==calculated.length||!timingSafeEqual(calculated,supplied)) return json(res,401,{error:'invalid_signature'});
      if(p.schema_version===4) {
        if(!validLifecycle(p)||req.headers['payment-event-id']!==p.event_id)return json(res,400,{error:'invalid_event'});
      } else {
      const fields=p.schema_version===1?FIELDS:p.schema_version===2?DONATION_FIELDS:p.schema_version===3?SUBSCRIPTION_FIELDS:null;
      if(!fields||Object.keys(p).sort().join(',')!==fields.join(',')||![p.event_id,p.site_id,p.payment_id].every(id=>typeof id==='string'&&UUID.test(id))||req.headers['payment-event-id']!==p.event_id||!TYPES.has(p.event_type)||!STATUSES.has(p.payment_status)||typeof p.occurred_at!=='string'||p.occurred_at.length>64||!Number.isFinite(Date.parse(p.occurred_at))||!(p.external_order_id===null||typeof p.external_order_id==='string'&&p.external_order_id.length>0&&p.external_order_id.length<=255&&!/[\u0000-\u001f\u007f]/.test(p.external_order_id))||!((p.amount_minor===null&&p.currency===null)||(Number.isSafeInteger(p.amount_minor)&&p.amount_minor>0&&typeof p.currency==='string'&&/^[A-Z]{3}$/.test(p.currency)))) return json(res,400,{error:'invalid_event'});
      if(p.schema_version===2&&(!(typeof p.donation_id==='string'&&UUID.test(p.donation_id))||!(typeof p.campaign_key==='string'&&/^[a-z0-9][a-z0-9-]{2,62}$/.test(p.campaign_key))||!(p.customer_id===null||typeof p.customer_id==='string'&&UUID.test(p.customer_id))||p.external_order_id!==null)) return json(res,400,{error:'invalid_event'});
      if(p.schema_version===3&&(![p.subscription_id,p.billing_record_id,p.customer_id,p.product_id,p.price_id].every(id=>typeof id==='string'&&UUID.test(id))||!['INITIAL','RENEWAL','PRORATION'].includes(p.billing_kind)||!Number.isSafeInteger(p.price_version)||p.price_version<1||p.price_version>2147483647||p.external_order_id!==null||p.amount_minor===null||![p.period_start,p.period_end].every(v=>typeof v==='string'&&v.length<=64&&Number.isFinite(Date.parse(v)))||Date.parse(p.period_start)>=Date.parse(p.period_end))) return json(res,400,{error:'invalid_event'});
      }
      const prior=events.get(p.event_id);
      if(prior) {
        if(prior.event_type!==p.event_type) return json(res,409,{error:'event_conflict'});
        prior.count++;duplicates++;
      } else {
        if(events.size>=10000) return json(res,503,{error:'capacity'});
        accessCache?.observe(p);
        accessRuntime?.observe(p);
        events.set(p.event_id,{event_id:p.event_id,event_type:p.event_type,count:1});
      }
      return json(res,204);
    } catch {if(!res.headersSent)json(res,400,{error:'invalid_request'});}
  });
  server.requestTimeout=5000;server.headersTimeout=5000;server.maxHeadersCount=30;
  server.on('clientError',(_error,socket)=>{socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');});
  return server;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href) {
  const accessRuntime=process.env.PAYMENT_STAGE4_TEST_RUNTIME==='true'?createOwnedAccessRuntime({backendUrl:process.env.PAYMENT_STAGE4_BACKEND_URL,clockUrl:process.env.PAYMENT_STAGE4_CONTROL_URL,capability:process.env.PAYMENT_STAGE4_CONTROL_KEY}):undefined;
  createReceiver({accessRuntime}).listen(8080,'0.0.0.0');
}
