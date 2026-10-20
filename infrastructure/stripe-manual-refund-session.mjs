import {createFormDiagnosticSettlement} from './stripe-form-diagnostic-settlement.mjs';

const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const provider=(value,prefix)=>typeof value==='string'&&new RegExp('^'+prefix+'[A-Za-z0-9_]{1,200}$').test(value);
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const fail=()=>{throw null;};
const fixed=()=>new Error('stripe_manual_refund_session_UNCONFIRMED');
const unique=values=>new Set(values).size===values.length;
const validId=value=>typeof value==='string'&&UUID.test(value);

/** An inert, one-use session. Only explicit methods acquire HTTP authority. */
export async function createManualRefundSession({runtime,cli,state},dependencies={}) {
  const target=state.target,fetcher=dependencies.fetch??globalThis.fetch;
  const now=dependencies.now??Date.now;
  const settlement=createFormDiagnosticSettlement({now,setTimeout:dependencies.setTimeout,clearTimeout:dependencies.clearTimeout});
  const work=new Set(),bodies=new Set(),resources=new Map(),releases=new WeakMap(),controllers=new Set();
  const proofs=[],acknowledged=[];
  let deadline=null,busy=false,failed=false,closed=false,closeUsed=false,resourceFailure=false;
  let baseline=false,rotated=false,rotationUsed=false,cookie='',csrf='',actor=null,original=null;
  const attempted=[false,false];
  const backend=target.endpoints.backend,receiver=target.endpoints.receiver;
  const paymentPath='/api/v1/admin/payments/'+target.paymentId;
  const allowed=new Set([
    'POST /api/admin/v1/auth/sessions','GET /api/admin/v1/auth/session',
    'GET /api/admin/v1/payment-accounts','GET /api/admin/v1/sites',
    'GET /api/admin/v1/sites/'+target.site.id+'/catalog',
    'GET /api/v1/admin/payments?site_id='+target.site.id+'&page=0&size=2&sort=CREATED_DESC',
    'GET '+paymentPath,'GET /api/v1/admin/provider-operations?payment_id='+target.paymentId,
    'POST /api/v1/admin/sites/'+target.site.id+'/callback/rotate-secret','POST '+paymentPath+'/refunds'
  ]);

  function limits(value,cleanup=false) {
    const keys=Object.keys(value??{});
    if(keys.some(k=>!['deadline','signal'].includes(k))||!Number.isSafeInteger(value?.deadline)
      ||value.deadline<=now()||value.signal!==undefined&&!(value.signal instanceof AbortSignal)
      ||value.signal?.aborted)fail();
    if(deadline===null) {
      if(cleanup||value.deadline>now()+1800000)fail();
      deadline=value.deadline;settlement.bind(deadline);
    }
    if(cleanup?value.deadline>deadline+120000:value.deadline!==deadline)fail();
    return Object.freeze({...value});
  }
  function check(context) {
    if(closed||failed||context.signal?.aborted||now()>=context.deadline)fail();
  }
  function track(body,set=work) {
    const promise=Promise.resolve().then(body);set.add(promise);
    promise.then(()=>set.delete(promise),()=>set.delete(promise));promise.catch(()=>{});return promise;
  }
  async function call(context,body) {
    check(context);
    const result=await settlement.wait(context,()=>track(()=>{check(context);return body();}));
    check(context);return result;
  }
  function own(response) {
    if(resources.has(response))fail();
    resources.set(response,()=>response.body?.cancel());
  }
  function release(response) {
    if(releases.has(response))return releases.get(response);
    const dispose=resources.get(response);if(!dispose)fail();
    const promise=track(async()=>{
      if(now()>=deadline+120000)fail();
      // Pending disposal stays strongly owned by work/resources. Once it has
      // settled, keep only the weak idempotency entry, even on rejection;
      // resourceFailure still prevents cleanup from being certified.
      try {await dispose();}finally {resources.delete(response);}
    });
    releases.set(response,promise);promise.catch(()=>{resourceFailure=true;});return promise;
  }
  async function request(path,context,{method='GET',body,expected=200,key,receiverRequest=false,login=false,guard=false,proofDeadline=context.deadline}={}) {
    check(context);
    if(receiverRequest?!['GET /inspection','POST /configure'].includes(method+' '+path):!allowed.has(method+' '+path))fail();
    // Login runs inside withAdminCredentials, whose exclusive callback has
    // already freshly proved topology. Reentering runtime.verify would fail.
    if(!login&&await call(context,()=>runtime.verify(context))!==true)fail();
    const origin=receiverRequest?receiver:backend;
    const u=new URL(origin);
    if(u.protocol!=='http:'||u.hostname!=='127.0.0.1'||u.username||u.password||u.pathname!=='/'||u.search||u.hash)fail();
    if(!receiverRequest&&!login&&(!cookie||!csrf))fail();
    const controller=new AbortController();controllers.add(controller);
    const bound=Math.min(context.deadline,proofDeadline,now()+10000);
    const signal=AbortSignal.any([controller.signal,AbortSignal.timeout(Math.max(1,bound-now())),...(context.signal?[context.signal]:[])]);
    const operation={deadline:bound,signal};
    let response;
    try {
      const headers={Accept:'application/json',...(body===undefined?{}:{'Content-Type':'application/json'}),
        ...(!receiverRequest&&!login?{Cookie:cookie,...(method==='GET'?{}:{'X-XSRF-TOKEN':csrf})}:{}),
        ...(key?{'Idempotency-Key':key}:{})};
      const raw=track(async()=>{
        check(context);if(signal.aborted||now()>=bound)fail();
        // No await may separate the listener fence from the financial fetch.
        if(guard&&cli.assertRefundListener(context)!==true)fail();
        const value=await fetcher(origin+path,{method,headers,redirect:'error',signal,
          ...(body===undefined?{}:{body:JSON.stringify(body)})});
        own(value);
        if(closed||failed||signal.aborted||now()>=bound)release(value).catch(()=>{});
        return value;
      });
      response=await settlement.wait(operation,()=>raw);check(context);
      if(response.redirected||response.status!==expected)fail();
      const reader=response.body?.getReader();
      if(reader)resources.set(response,async()=>{try{await reader.cancel();}finally{reader.releaseLock();}});
      let size=0;const chunks=[];
      if(reader)for(;;) {
        const part=await settlement.wait(operation,()=>track(()=>reader.read()));
        check(context);if(part.done)break;
        size+=part.value.byteLength;if(size>65536)fail();chunks.push(Buffer.from(part.value));
      }
      check(context);if(signal.aborted||now()>=bound)fail();
      const bytes=Buffer.concat(chunks);
      if(expected===204&&bytes.length)fail();
      const value=bytes.length?JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes)):null;
      return {value,cookies:login?response.headers.getSetCookie():null};
    }finally {
      controller.abort();controllers.delete(controller);
      if(response)await release(response);
    }
  }
  const read=async(path,context,options)=>(await request(path,context,options)).value;
  function proofPhase(context) {
    const until=Math.min(context.deadline,now()+45000);
    const controller=new AbortController();controllers.add(controller);
    const signal=AbortSignal.any([controller.signal,...(context.signal?[context.signal]:[])]);
    const timer=(dependencies.setTimeout??setTimeout)(()=>controller.abort(),Math.max(1,until-now()));
    return {until,delegate:Object.freeze({deadline:context.deadline,signal}),wait:{deadline:until,signal},
      close(){(dependencies.clearTimeout??clearTimeout)(timer);controller.abort();controllers.delete(controller);}};
  }
  async function actorProof(context) {
    const value=await read('/api/admin/v1/auth/session',context);
    if(!validId(value?.id)||!Number.isFinite(Date.parse(value.expires_at))||Date.parse(value.expires_at)<=now()
      ||actor!==null&&actor!==value.id)fail();
    actor??=value.id;
  }
  async function binding(context) {
    if(cli.assertRefundListener(context)!==true||await call(context,()=>cli.validateBinding(context))!==true)fail();
    await actorProof(context);
    const accounts=await read('/api/admin/v1/payment-accounts',context);
    const sites=await read('/api/admin/v1/sites',context);
    if(!Array.isArray(accounts)||accounts.length!==1||!Array.isArray(sites)||sites.length!==1)fail();
    const a=accounts[0],s=sites[0];
    if(a.id!==target.account.id||a.key!==target.account.key||a.external_account_id!==target.account.externalId
      ||a.provider!=='STRIPE'||a.mode!=='SANDBOX'||a.status!=='ACTIVE'||s.id!==target.site.id||s.key!==target.site.key
      ||s.status!=='ACTIVE'||s.payment_account_id!==a.id||!same(s.authentication_modes,['EMAIL_OTP'])
      ||s.payment_method_policy?.mode!=='ALLOWLIST'||!same(s.payment_method_policy.methods,['CARD']))fail();
    const catalog=await read('/api/admin/v1/sites/'+s.id+'/catalog',context);
    if(!Array.isArray(catalog?.prices)||catalog.prices.length!==1
      ||!Array.isArray(catalog.products)||catalog.products.length!==1)fail();
    const price=catalog.prices[0];
    const product=catalog.products[0];
    if(!validId(product.id)||product.id!==price.product_id||product.site_id!==s.id||product.status!=='ACTIVE'
      ||product.key!=='s5a-product-'+target.originalRunId.replaceAll('-',''))fail();
    if(price.id!==target.price.id||price.site_id!==s.id||price.price_key!==target.price.key||price.version!==1
      ||price.provider_price_id!==target.price.providerPriceId||price.type!=='ONE_TIME'||price.status!=='PUBLISHED'
      ||price.amount_minor!==2000||price.currency!=='EUR'||price.tax_mode!=='NONE')fail();
  }
  function cashEvent(d,event,amount,reference) {
    if(!event||!validId(event.id)||event.source!=='PROVIDER_EVENT'||!provider(event.provider_event_id,'evt_')
      ||event.provider_object_id!==reference||event.amount_minor!==amount||event.currency!=='EUR')fail();
    const callback=event.event_type==='PAYMENT_SUCCEEDED'?'payment.succeeded':'refund.succeeded';
    const out=d.outbox_events.filter(o=>o.id===event.id&&o.callback_type===callback);
    if(out.length!==1||out[0].status!=='DELIVERED')return false;
    return d.deliveries.some(v=>v.outbox_event_id===event.id&&v.status==='SUCCEEDED'
      &&Number.isInteger(v.http_status)&&v.http_status>=200&&v.http_status<300);
  }
  async function detail(context,total,ids,proofDeadline=context.deadline) {
    const d=await read(paymentPath,context,{proofDeadline}),p=d?.payment;
    if(!p||p.payment_id!==target.paymentId||p.site_id!==target.site.id||p.payment_account_id!==target.account.id
      ||p.provider!=='STRIPE'||p.source!=='CATALOG'||p.price_id!==target.price.id||p.price_key!==target.price.key
      ||p.price_version!==1||p.amount_minor!==2000||p.currency!=='EUR'||p.tax_mode!=='NONE'
      ||p.customer_id!==null||p.donation_id!==null||p.subscription_id!==null||p.external_order_id!==null
      ||!['attempts','events','refunds','outbox_events','deliveries'].every(k=>Array.isArray(d[k])&&d[k].length<=100)
      ||!unique(d.events.map(e=>e.id))||!unique(d.refunds.map(r=>r.id))||!unique(d.outbox_events.map(o=>o.id))
      ||d.refunds.some(r=>!ids.includes(r.id))||d.attempts.length!==1)fail();
    const attempt=d.attempts[0];
    if(!validId(attempt.id)||attempt.attempt_number!==1||attempt.status!=='SUCCEEDED'
      ||attempt.provider_intent_reference!==target.intentId||attempt.payment_method_family!=='CARD')fail();
    const page=await read('/api/v1/admin/payments?site_id='+target.site.id+'&page=0&size=2&sort=CREATED_DESC',context,{proofDeadline});
    if(page?.total!==1||page.page!==0||page.size!==2||page.sort!=='CREATED_DESC'
      ||!Array.isArray(page.items)||page.items.length!==1||!same(page.items[0],p))fail();
    const operations=await read('/api/v1/admin/provider-operations?payment_id='+target.paymentId,context,{proofDeadline});
    if(!Array.isArray(operations)||operations.length!==d.refunds.length+1
      ||!unique(operations.map(o=>o.operation_id))||!unique(operations.map(o=>o.resource_id)))fail();
    const prepare=operations.filter(o=>o.kind==='PAYMENT_PREPARE');
    if(prepare.length!==1||prepare[0].operation_id!==target.operationId||prepare[0].resource_id!==target.paymentId
      ||prepare[0].reference!==target.intentId)fail();
    for(const op of operations) {
      if(!validId(op.operation_id)||op.payment_id!==target.paymentId||op.site_id!==target.site.id
        ||op.account_id!==target.account.id||op.account_mode!=='SANDBOX'||op.status!=='ACKNOWLEDGED'
        ||op.manual_review_required!==false)fail();
      if(op.kind!=='PAYMENT_PREPARE') {
        const refund=d.refunds.find(r=>r.id===op.resource_id);
        if(op.kind!=='REFUND_CREATE'||!refund||op.reference!==refund.provider_refund_reference)fail();
      }
    }
    const success=d.events.filter(e=>e.event_type==='PAYMENT_SUCCEEDED');
    if(success.length!==1||!cashEvent(d,success[0],2000,target.intentId))fail();
    const immutable={attempt,success:success[0],events:d.events.filter(e=>!e.event_type.startsWith('REFUND_'))};
    if(original&&!same(immutable,original))fail();
    if(!original)original=structuredClone(immutable);
    if(d.events.some(e=>!validId(e.id)||!['PAYMENT_CREATED','PAYMENT_METHOD_REQUIRED','ATTEMPT_CREATED',
      'PAYMENT_PROCESSING','PAYMENT_SUCCEEDED','REFUND_PROCESSING','REFUND_SUCCEEDED'].includes(e.event_type))
      ||d.events.filter(e=>e.event_type==='PAYMENT_CREATED').length!==1
      ||d.events.filter(e=>e.event_type==='ATTEMPT_CREATED').length!==1
      ||d.outbox_events.some(o=>!d.events.some(e=>e.id===o.id&&({PAYMENT_PROCESSING:'payment.processing',
        PAYMENT_SUCCEEDED:'payment.succeeded',REFUND_PROCESSING:'refund.processing',REFUND_SUCCEEDED:'refund.succeeded'})[e.event_type]===o.callback_type))
      ||d.deliveries.some(v=>!d.outbox_events.some(o=>o.id===v.outbox_event_id)))fail();
    const status=total===0?'SUCCEEDED':total===700?'PARTIALLY_REFUNDED':'REFUNDED';
    return {d,complete:p.status===status&&p.refunded_amount_minor===total&&p.refundable_amount_minor===2000-total&&d.refunds.length===ids.length};
  }
  async function receipts(d,context,proofDeadline) {
    const inspection=await read('/inspection',context,{receiverRequest:true,proofDeadline});
    if(!Array.isArray(inspection?.events)||inspection.events.length>100||inspection.count!==inspection.events.length
      ||!Number.isSafeInteger(inspection.duplicates)||inspection.duplicates<0||!unique(inspection.events.map(e=>e.event_id))
      ||inspection.events.some(e=>!Number.isSafeInteger(e.count)||e.count<1
        ||!d.outbox_events.some(o=>o.id===e.event_id&&o.callback_type===e.event_type)))fail();
    return inspection.events;
  }
  async function localProof(context,index,proofDeadline) {
    const total=index?2000:700,ids=acknowledged.slice(0,index+1);
    const {d,complete}=await detail(context,total,ids,proofDeadline);
    const successes=d.events.filter(e=>e.event_type==='REFUND_SUCCEEDED');
    if(successes.length>ids.length||!unique(successes.map(e=>e.provider_event_id))
      ||!unique(successes.map(e=>e.provider_object_id)))fail();
    if(!complete)return null;
    const received=await receipts(d,context,proofDeadline);
    const found=[];
    for(let i=0;i<=index;i++) {
      const amount=i?1300:700,refund=d.refunds.find(r=>r.id===ids[i]);
      if(!refund||refund.amount_minor!==amount||refund.currency!=='EUR'||refund.reason_code!=='REQUESTED_BY_CUSTOMER')fail();
      if(refund.status!=='SUCCEEDED')return null;
      if(!provider(refund.provider_refund_reference,'re_'))fail();
      const events=successes.filter(e=>e.provider_object_id===refund.provider_refund_reference);
      if(events.length>1)fail();if(!events.length)return null;
      const event=events[0];
      if(!cashEvent(d,event,amount,refund.provider_refund_reference))return null;
      if(!received.some(e=>e.event_id===event.id&&e.event_type==='refund.succeeded'))return null;
      const value={refundId:refund.id,providerReference:refund.provider_refund_reference,
        providerEventId:event.provider_event_id,amountMinor:amount,totalMinor:i?2000:700};
      if(proofs[i]&&!same(value,proofs[i].identity))fail();
      found.push({identity:value,eventId:event.id});
      if(proofs[i]&&proofs[i].eventId!==event.id)fail();
    }
    return found;
  }
  async function verifyBaseline(context) {
    if(baseline||target.callbackVersion!==1)fail();
    if(await call(context,()=>runtime.withAdminCredentials(async credentials=>{
      check(context);
      const result=await request('/api/admin/v1/auth/sessions',context,{method:'POST',body:credentials,expected:204,login:true});
      check(context);
      const values=result.cookies;
      const tokens=name=>values.filter(v=>v.startsWith(name+'=')).map(v=>v.split(';')[0]);
      const sessions=tokens('PAYMENT_ADMIN_SESSION'),xsrfs=tokens('XSRF-TOKEN');
      if(sessions.length!==1||xsrfs.length!==1||!/^PAYMENT_ADMIN_SESSION=[A-Za-z0-9_-]{1,512}$/.test(sessions[0])
        ||!/^XSRF-TOKEN=[A-Za-z0-9_-]{1,512}$/.test(xsrfs[0]))fail();
      cookie=sessions[0]+'; '+xsrfs[0];csrf=xsrfs[0].slice(11);return true;
    },context))!==true)fail();
    await binding(context);
    const {d,complete}=await detail(context,0,[]);
    if(!complete||d.events.some(e=>e.event_type.startsWith('REFUND_')))fail();
    baseline=true;return true;
  }
  async function rotateCallback(context) {
    if(!baseline||rotationUsed)fail();rotationUsed=true;
    await binding(context);const current=await detail(context,0,[]);if(!current.complete)fail();
    if(await call(context,()=>state.beginRotation(context))!==true)fail();
    let reveal=await read('/api/v1/admin/sites/'+target.site.id+'/callback/rotate-secret',context,{method:'POST',guard:true});
    try {
      if(reveal?.site_id!==target.site.id||reveal.endpoint_url!=='http://callback-receiver:8080/callback'
        ||reveal.enabled!==true||reveal.secret_version!==2||typeof reveal.secret!=='string'
        ||!/^[A-Za-z0-9_-]{43}$/.test(reveal.secret)||Buffer.from(reveal.secret,'base64url').length!==32)fail();
      if(await call(context,()=>state.ackRotation(2,context))!==true
        ||await call(context,()=>state.beginConfigure(2,context))!==true)fail();
      await request('/configure',context,{method:'POST',expected:204,receiverRequest:true,
        body:{secret:reveal.secret,secret_version:2}});
      if(await call(context,()=>state.ackConfigure(2,context))!==true)fail();
      rotated=true;return true;
    }finally {if(reveal)reveal.secret='';reveal=null;}
  }
  async function refund(index,context) {
    if(!rotated||attempted[index]||index===1&&!proofs[0])fail();attempted[index]=true;
    await binding(context);
    if(index) {
      if(!await localProof(context,0,context.deadline))fail();
    } else if(!(await detail(context,0,[])).complete)fail();
    const leg=await call(context,()=>state.beginRefund(index,context));
    if(!Object.isFrozen(leg)||leg.phase!=='DISPATCHING'||leg.amountMinor!==(index?1300:700)
      ||typeof leg.key!=='string'||leg.key.length<1||leg.key.length>255||/[^A-Za-z0-9:_-]/.test(leg.key)
      ||leg.refundId!==null||leg.providerReference!==null)fail();
    const result=await read(paymentPath+'/refunds',context,{method:'POST',expected:202,key:leg.key,guard:true,
      body:{amount_minor:leg.amountMinor,currency:'EUR',reason_code:'REQUESTED_BY_CUSTOMER'}});
    if(!validId(result?.refund_id)||result.payment_id!==target.paymentId||result.amount_minor!==leg.amountMinor
      ||result.currency!=='EUR'||!['PENDING','PROCESSING','SUCCEEDED'].includes(result.status)
      ||acknowledged.includes(result.refund_id))fail();
    if(await call(context,()=>state.ackRefund(index,{refundId:result.refund_id},context))!==true)fail();
    acknowledged[index]=result.refund_id;
    const phase=proofPhase(context);
    try {
      let found;
      for(let probe=0;probe<30&&now()<phase.until;probe++) {
        check(context);found=await call(phase.wait,()=>localProof(phase.delegate,index,phase.until));
        if(found)break;
        const delay=dependencies.probeDelay??100;
        if(!Number.isInteger(delay)||delay<0||delay>250)fail();
        if(delay)await call(phase.wait,()=>new Promise(resolve=>setTimeout(resolve,Math.min(delay,Math.max(0,phase.until-now())))));
      }
      if(!found||now()>=phase.until)fail();
      const current=found[index];
      const providerProof=await call(phase.wait,()=>cli.inspectRefund(Object.freeze({...current.identity}),phase.delegate));
      if(providerProof?.providerVerified!==true||providerProof.webhookVerified!==true)fail();
      const proof=Object.freeze({...current.identity,providerVerified:true,webhookVerified:true,journalVerified:true,callbackVerified:true});
      if(await call(phase.wait,()=>state.confirmRefund(index,proof,phase.delegate))!==true)fail();
      proofs[index]=current;return proof;
    }finally {phase.close();}
  }
  async function verifyFinal(context) {
    if(!proofs[0]||!proofs[1])fail();
    const phase=proofPhase(context);
    try {
      await call(phase.wait,()=>binding(phase.delegate));
      if(!await call(phase.wait,()=>localProof(phase.delegate,1,phase.until)))fail();
      const proof=await call(phase.wait,()=>cli.inspectRefund(Object.freeze({...proofs[1].identity}),phase.delegate));
      if(proof?.providerVerified!==true||proof.webhookVerified!==true)fail();
      return Object.freeze({partialConfirmed:true,remainderConfirmed:true,providerVerified:true,
        webhookVerified:true,journalVerified:true,callbackVerified:true});
    }finally {phase.close();}
  }
  async function run(value,body) {
    if(busy){failed=true;throw fixed();}busy=true;
    try {
      const context=limits(value);check(context);
      const result=await settlement.wait(context,()=>track(()=>body(context),bodies));
      check(context);return result;
    }catch {failed=true;throw fixed();}
    finally {busy=false;}
  }
  async function close(value) {
    if(closeUsed)throw fixed();closeUsed=true;closed=true;
    for(const controller of controllers)controller.abort();
    try {
      if(deadline===null) {
        if(work.size||bodies.size||resources.size)fail();return true;
      }
      const context=limits(value,true);
      for(;;) {
        for(const response of resources.keys())release(response).catch(()=>{});
        const waiting=[...work,...bodies];
        if(!waiting.length)break;
        await settlement.wait(context,()=>Promise.allSettled(waiting));
      }
      if(resources.size||resourceFailure)fail();return true;
    }catch {failed=true;throw fixed();}
    finally {cookie='';csrf='';actor=null;}
  }
  return Object.freeze({verifyBaseline:value=>run(value,verifyBaseline),rotateCallback:value=>run(value,rotateCallback),
    refundPartial:value=>run(value,context=>refund(0,context)),refundRemainder:value=>run(value,context=>refund(1,context)),
    verifyFinal:value=>run(value,verifyFinal),close});
}
