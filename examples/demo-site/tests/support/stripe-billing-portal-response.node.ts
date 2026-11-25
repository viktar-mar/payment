import assert from 'node:assert/strict';
import test from 'node:test';
// @ts-ignore Node runs this support suite directly, outside Playwright discovery.
import {createPortal201Guard} from './stripe-billing-portal-response.ts';

const backendOrigin='http://127.0.0.1:18080',demoOrigin='http://127.0.0.1:18081';
const returnUrl=demoOrigin+'/?fixture=portal';
const hostedUrl='https://billing.stripe.com/p/session?secret=test_syntheticPortal';
const refusal=/^Error: billing_BROWSER_PORTAL_RECEIPT$/;
const id=(n:number)=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');

function fixture({sizeOffset=0,readOffset=0,hasDeclaredLength=true}={}) {
  const frame={},document={};
  const authority={customerId:id(1),siteId:id(2),subscriptionId:id(3),bearer:'Bearer '+'A'.repeat(43),
    revision:4,frame,document,returnUrl};
  const endpoint=backendOrigin+'/api/v1/customer/subscriptions/'+authority.subscriptionId+'/portal-sessions';
  const expiresAt=new Date(Date.now()+60000).toISOString();
  const bytes=Buffer.from(JSON.stringify({navigation_url:hostedUrl,expires_at:expiresAt,simulated:false}));
  let current={...authority},continued=0,armed=0,read=0;
  let paired:typeof response|null=null;
  const request={
    url:()=>endpoint,method:()=>'POST',frame:()=>frame,redirectedFrom:()=>null,serviceWorker:()=>null,
    isNavigationRequest:()=>false,postDataBuffer:()=>Buffer.from(JSON.stringify({return_url:returnUrl})),
    existingResponse:()=>paired,
    allHeaders:async()=>({'authorization':authority.bearer,'origin':demoOrigin,'content-type':'application/json','idempotency-key':id(4)}),
  };
  const response={
    url:()=>endpoint,request:()=>request,status:()=>201,fromServiceWorker:()=>false,
    headerValue:async(name:string)=>name==='content-length'
      ? (hasDeclaredLength?String(bytes.length):null)
      : ({'content-type':'application/json','cache-control':'no-store'} as Record<string,string>)[name]??null,
  };
  const guard=createPortal201Guard({backendOrigin,demoOrigin,authority,policy:{portalReturnUrl:returnUrl,portalPolicyVersion:1}},
    ()=>current,{deadline:Date.now()+1000},async(candidate,result,limits)=>{
      assert.equal(candidate,request);
      assert.equal(result,response);
      assert.ok(Object.isFrozen(limits));
      read++;
      return {bytes:bytes.subarray(0,bytes.length+readOffset),measured:bytes.length+sizeOffset};
    });
  const claim=async()=>{await guard.claimRequest(request);paired=response;};
  const hosted=(url=hostedUrl)=>{
    const navigation={...request,url:()=>url,method:()=>'GET',isNavigationRequest:()=>true,postDataBuffer:()=>null,existingResponse:()=>null};
    return {request:()=>navigation,continue:async()=>{continued++;}};
  };
  const arm=async():Promise<true>=>{armed++;return true;};
  return {claim,request,response,guard,expiresAt,hosted,arm,read:()=>read,continued:()=>continued,armed:()=>armed,
    replaceDocument:()=>{current={...current,document:{}};}};
}

test('only the original paired response can use its captured body',async()=>{
  const f=fixture();
  await f.claim();
  await assert.rejects(f.guard.observe201({...f.response}),refusal);
  assert.equal(f.read(),0);
  assert.equal(f.continued(),0);
});

test('measured bytes and the paired response Content-Length must agree',async()=>{
  for(const difference of [-1,1,32768]){
    const f=fixture({sizeOffset:difference});
    await f.claim();
    await assert.rejects(f.guard.observe201(f.response),refusal);
    assert.equal(f.read(),1);
  }
});

test('missing Content-Length is refused before the captured body is obtained',async()=>{
  const f=fixture({hasDeclaredLength:false});
  await f.claim();
  await assert.rejects(f.guard.observe201(f.response),refusal);
  assert.equal(f.read(),0);
});

test('a truncated capture is refused even when declared and measured sizes agree',async()=>{
  const f=fixture({readOffset:-1});
  await f.claim();
  await assert.rejects(f.guard.observe201(f.response),refusal);
  assert.equal(f.read(),1);
});

test('an exact receipt enables one GET and rejects a repeated GET before arming',async()=>{
  const f=fixture();
  await f.claim();
  assert.deepEqual(await f.guard.observe201(f.response),{expiresAt:f.expiresAt});
  await f.guard.continueHostedNavigation(f.hosted(),f.arm);
  await assert.rejects(f.guard.continueHostedNavigation(f.hosted(),f.arm),refusal);
  assert.equal(f.armed(),1);
  assert.equal(f.continued(),1);
});

test('a different capability on the same Stripe path cannot arm the original receipt',async()=>{
  const f=fixture();
  await f.claim();
  await f.guard.observe201(f.response);
  await assert.rejects(f.guard.continueHostedNavigation(f.hosted(hostedUrl+'Altered'),f.arm),refusal);
  assert.equal(f.armed(),0);
  assert.equal(f.continued(),0);
});

test('a same-frame checkout replacement between arming and continuation revokes the receipt',async()=>{
  const f=fixture();
  await f.claim();
  await f.guard.observe201(f.response);
  await assert.rejects(f.guard.continueHostedNavigation(f.hosted(),async()=>{
    f.replaceDocument();
    return true as const;
  }),refusal);
  assert.equal(f.continued(),0);
});
