import test from 'node:test';
import assert from 'node:assert/strict';
import {createContext,runInContext} from 'node:vm';
import type {Browser,Request,Response,Route} from '@playwright/test';
import {browserCleanupConfirmed,browserDiagnostic} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';
// @ts-ignore Native Node executes the same protocol fixture registration.
import {registerChromiumProtocolFixture} from './stripe-billing-chromium-route.ts';
// @ts-ignore Native Node executes the same source as Playwright.
import {optionalTrialSetupExchange,runOptionalTrialSetupBrowser,type OptionalTrialSetupPermit,type OptionalTrialScriptWitness} from './stripe-billing-optional-trial-setup.ts';

const permit:OptionalTrialSetupPermit = {
  setupIntentId:'seti_owned', publishableKey:'pk_test_fixture', clientSecret:'seti_owned_secret_fixture',
  expiresAt:new Date(Date.now()+60_000).toISOString(),
};
const confirmUrl='https://api.stripe.com/v1/setup_intents/seti_owned/confirm';
const frameOrigin='https://js.stripe.com';
const testStripeScript='https://js.stripe.com/endive/stripe.js?advancedFraudSignals=false';
const redacted=/^Error: billing_OPTIONAL_TRIAL_SETUP_BROWSER$/;
function fixture(){
  const headers:Record<string,string>={origin:frameOrigin,'content-type':'application/x-www-form-urlencoded'};
  const form=new URLSearchParams({client_secret:permit.clientSecret,key:permit.publishableKey,'payment_method_data[type]':'card'});
  const request={
    url:()=>confirmUrl,method:()=>'POST',redirectedFrom:()=>null,serviceWorker:()=>null,
    isNavigationRequest:()=>false,resourceType:()=>'fetch',postData:()=>form.toString(),allHeaders:async()=>headers,
  };
  const value:Record<string,unknown>={object:'setup_intent',id:permit.setupIntentId,client_secret:permit.clientSecret,
    livemode:false,status:'succeeded',next_action:null};
  const response={
    request:()=>request,url:()=>confirmUrl,status:()=>200,fromServiceWorker:()=>false,
    body:async()=>Buffer.from(JSON.stringify(value)),
  };
  const exchange=optionalTrialSetupExchange(permit);
  return {headers,form,value,request,response,exchange,
    claim:(trusted=true)=>exchange.claim(request as unknown as Request,headers,frameOrigin,trusted),
    verify:()=>exchange.verify(response as unknown as Response)};
}

test('pairs one trusted original SetupIntent POST and its succeeded test-mode response',async()=>{
  const f=fixture();
  f.claim();
  assert.equal(f.exchange.isResponse(f.response as unknown as Response),true);
  assert.throws(()=>f.claim(),redacted);
  await f.verify();
});

for(const fault of ['untrusted','wrong path','payment intent','secret','duplicate secret','wrong key','mixed keys','foreign frame',
 'confirmation token','other method','connect override','cookie','navigation','redirect']){
  test('refuses setup confirmation before forwarding on '+fault,()=>{
    const f=fixture();
    switch(fault){
      case 'wrong path':f.request.url=()=>confirmUrl.replace('seti_owned','seti_other');break;
      case 'payment intent':f.request.url=()=>confirmUrl.replace('/setup_intents/seti_owned','/payment_intents/pi_other');break;
      case 'secret':f.form.set('client_secret','seti_other_secret_fixture');break;
      case 'duplicate secret':f.form.append('client_secret',permit.clientSecret);break;
      case 'wrong key':f.form.set('key','pk_test_foreign');break;
      case 'mixed keys':f.headers.authorization='Bearer pk_test_other';break;
      case 'foreign frame':f.headers.origin='https://other.test';break;
      case 'confirmation token':f.form.set('confirmation_token','ctoken_other');break;
      case 'other method':f.form.set('payment_method','pm_other');break;
      case 'connect override':f.headers['stripe-account']='acct_other';break;
      case 'cookie':f.headers.cookie='anything';break;
      case 'navigation':f.request.isNavigationRequest=()=>true;break;
      case 'redirect':Object.assign(f.request,{redirectedFrom:()=>({})});break;
    }
    assert.throws(()=>f.claim(fault!=='untrusted'),redacted);
    assert.equal(f.exchange.isResponse(f.response as unknown as Response),false);
  });
}

for(const fault of ['other request','other intent','other secret','live','requires action','action despite success','service worker','failed HTTP','oversize','duplicate id alias','duplicate test mode','duplicate secret alias']){
  test('rejects an unverified setup response on '+fault,async()=>{
    const f=fixture();f.claim();
    switch(fault){
      case 'other request':f.response.request=()=>({...f.request});break;
      case 'other intent':f.value.id='seti_other';break;
      case 'other secret':f.value.client_secret='seti_other_secret_fixture';break;
      case 'live':f.value.livemode=true;break;
      case 'requires action':f.value.status='requires_action';break;
      case 'action despite success':f.value.next_action={type:'redirect_to_url'};break;
      case 'service worker':f.response.fromServiceWorker=()=>true;break;
      case 'failed HTTP':f.response.status=()=>402;break;
      case 'oversize':f.response.body=async()=>Buffer.alloc(65_537);break;
      case 'duplicate id alias':f.response.body=async()=>Buffer.from(JSON.stringify(f.value)
        .replace('"id":','"\\u0069d":"seti_other","id":'));break;
      case 'duplicate test mode':f.response.body=async()=>Buffer.from(JSON.stringify(f.value)
        .replace('"livemode":','"livemode":true,"livemode":'));break;
      case 'duplicate secret alias':f.response.body=async()=>Buffer.from(JSON.stringify(f.value)
        .replace('"client_secret":','"client_\\u0073ecret":"seti_other_secret_fixture","client_secret":'));break;
    }
    await assert.rejects(f.verify(),redacted);
  });
}

// Runs the real routing and page callbacks against VM DOM and CDP values only.
function checkout(mode:'normal'|'untrusted'|'foreign frame'|'close fails'|'repeat'|'card reload'|'sibling confirm'|'main transport'|'approved helper'|'card reload during helper trust'|'script redirect'|'uncommitted child'|'uncommitted child cookie'|'uncommitted child foreign parent'|'easel asset'|'trusted types asset'|'easel query'|'easel bare query'|'easel delayed'|'other main asset'|'delayed element ready'|'element load error'|'missing card number'|'element no session'|'element no response'|'element no ready event'|'unobserved helper read'|'helper without response'|'late helper response'|'activation dependency'|'activation dependency reload'|'activation dependency query'|'activation alternate fingerprint',onHelperResponse=()=>{}){
  const picked={...permit,setupIntentId:'seti_'+mode.replaceAll(' ',''),
    clientSecret:'seti_'+mode.replaceAll(' ','')+'_secret_fixture'};
  const url='https://api.stripe.com/v1/setup_intents/'+picked.setupIntentId+'/confirm';
  const sessionUrl='https://api.stripe.com/v1/elements/sessions';
  const easel='https://js.stripe.com/v3/fingerprinted/js/elements-easel-frame-'+'a'.repeat(32)+'.js';
  const activationDependency='https://js.stripe.com/v3/fingerprinted/js/trusted-types-checker-e80b3cae3b1db2e27d3062d4e7b03473.js';
  const activationAsset=mode==='activation dependency'||mode==='activation dependency reload'?activationDependency:mode==='activation dependency query'?activationDependency+'?fixture=1':
    mode==='activation alternate fingerprint'?activationDependency.replace('e80b3cae3b1db2e27d3062d4e7b03473','b'.repeat(32)):undefined;
  const extraScript=activationAsset??(mode==='easel asset'||mode==='easel delayed'?easel:mode==='easel query'?easel+'?mode=synthetic':mode==='easel bare query'?easel+'?':
    mode==='trusted types asset'?easel.replace('elements-easel-frame','trusted-types-checker'):
    mode==='other main asset'?easel.replace('elements-easel-frame','unreviewed'):undefined);
  let path='about:blank',closed=0,nextId=0,trustChecks=0,childLoads=0,extraLoads=0;
  let routeHandler:((route:Route)=>Promise<void>)|undefined;
  const devtools=new Map<string,(event:unknown)=>void|Promise<void>>();
  const listeners=new Map<string,((event?:unknown)=>void)[]>();
  const emit=(name:string,event?:unknown)=>{for(const listener of listeners.get(name)??[])listener(event);};
  const writes:string[]=[];
  const scriptWitnesses:OptionalTrialScriptWitness[]=[];
  const found=Promise.withResolvers<Response>();
  const assetPaused=Promise.withResolvers<void>(), resumeAsset=Promise.withResolvers<void>();
  let delayedAsset:Promise<void>|undefined;
  let governingResponse:((r:Response)=>boolean)|undefined;
  const host={isConnected:true};
  const cardOwner={isConnected:true};
  let pendingPresentation:Promise<void>|undefined;
  const document={
    hidden:false,activeElement:undefined as unknown,
    querySelector:(selector:string)=>selector==='#payment-element'?host:
      selector==='#confirm-setup' || selector==='#confirm-setup[data-confirmed="true"]'&&button.dataset.confirmed?button:
      selector.includes('Secure payment input frame')?cardOwner:null,
    addEventListener:()=>{},
  };
  class TestButton {
    dataset:Record<string,string>={};isConnected=true;
    handlers:{event:string;fn:(event:{isTrusted:boolean})=>void;capture:boolean}[]=[];
    private inactive=true;
    get disabled(){return this.inactive;}
    set disabled(value:boolean){this.inactive=value;if(value&&document.activeElement===this)document.activeElement=undefined;}
    addEventListener(event:string,fn:(event:{isTrusted:boolean})=>void,options?:{capture:boolean}) {
      this.handlers.push({event,fn,capture:options?.capture??false});
    }
  }
  const button=new TestButton();
  const paymentEvents=new Map<string,(event?:unknown)=>void>();
  let rendered=false;
  const location={get href(){return path;}};
  const window={
    get top(){return window;},addEventListener:()=>{},
    Stripe:(key:string)=>{
      assert.equal(key,picked.publishableKey);
      return {
        elements:(options:{clientSecret:string})=>{
          assert.equal(options.clientSecret,picked.clientSecret);
          return {create:()=>({on:(name:string,fn:(event?:unknown)=>void)=>paymentEvents.set(name,fn),
            mount:()=>{
              if(mode==='element no response' || mode==='element no ready event'){
                pendingPresentation=request(sessionUrl,'GET',stripe,false,'fetch');
                return;
              }
              if(mode==='element no session')return;
              if(mode.startsWith('uncommitted child'))
                pendingPresentation=request(frameOrigin+'/v3/controller-fixture.html','GET',uncommitted,true,'document');
              if(['approved helper','card reload during helper trust','unobserved helper read','helper without response','late helper response'].includes(mode))
                pendingPresentation=request(url.replace('/confirm','')+'?'+new URLSearchParams({
                key:picked.publishableKey,client_secret:picked.clientSecret}), 'GET',sibling,false,'fetch');
              const render=()=>{
                rendered=true;
                paymentEvents.get('ready')!();paymentEvents.get('change')!({complete:true});
              };
              if(mode==='element load error')paymentEvents.get('loaderror')!({error:{message:'synthetic private error'}});
              else if(mode==='delayed element ready')setImmediate(render);
              else render();
            }})};
        },
        confirmSetup:async()=>{
          if(activationAsset)await request(activationAsset,'GET',main,false,'script');
          if(delayedAsset){
            resumeAsset.resolve();
            await delayedAsset;
          }
          if(mode==='card reload')emit('framenavigated',stripe);
          const frame=mode==='foreign frame'?foreign:mode==='main transport'?main:
            ['sibling confirm','approved helper','card reload during helper trust','helper without response','late helper response'].includes(mode)?sibling:stripe;
          await request(url,'POST',frame,false,'fetch');
          if(mode==='repeat')await request(url,'POST',stripe,false,'fetch');
          return {setupIntent:{id:picked.setupIntentId,status:'succeeded'}};
        },
      };
    },
  };
  const script=createContext({window,document,location,HTMLElement:Object,HTMLButtonElement:TestButton});
  function evaluate<T>(fn:Function,argument?:unknown):T {
    Object.assign(script,{argument});
    return runInContext('('+fn.toString()+')(argument)',script) as T;
  }
  const main={page:()=>page,url:()=>path,isDetached:()=>false,parentFrame:()=>null as unknown,name:()=>''};
  const stripe={page:()=>page,url:()=>frameOrigin+'/fixture',isDetached:()=>false,parentFrame:()=>main,name:()=>'stripe',
    locator:(selector?:string)=>({fill:async()=>{
      if(!rendered || mode==='missing card number'&&selector==='input[name="number"]')throw Error('synthetic field unavailable');
      await pendingPresentation;
    },isVisible:async()=>false}),
    waitForURL:async(predicate:(value:URL)=>boolean)=>{assert.equal(predicate(new URL(stripe.url())),true);}};
  const sibling={...stripe,name:()=>'sibling'};
  const foreign={...stripe,name:()=>'foreign',url:()=>'https://other.test/fixture'};
  const uncommitted={...stripe,url:()=>'',name:()=>'',parentFrame:()=>mode==='uncommitted child foreign parent'?foreign:main,
    frameElement:async()=>({evaluate:async()=> 'new-iframe',contentFrame:async()=>uncommitted,dispose:async()=>{}})};
  const page={
    mainFrame:()=>main,url:()=>path,context:()=>context,on:(name:string,fn:(event?:unknown)=>void)=>{
      listeners.set(name,[...listeners.get(name)??[],fn]);
    },
    goto:async(target:string)=>{
      await request(target,'GET',main,true,'document');
      path=target;
      await request(testStripeScript,'GET',main,false,'script');
      if(extraScript && !activationAsset){
        const load=request(extraScript,'GET',main,false,'script');
        if(mode==='easel delayed'){
          delayedAsset=load;
          await assetPaused.promise;
        }else await load;
      }
    },
    evaluate:async(fn:Function,arg?:unknown)=>evaluate(fn,arg),
    locator:()=>({frameLocator:()=>({locator:()=>stripe.locator()}),locator:()=>({
      elementHandle:async()=>({contentFrame:async()=>stripe,evaluate:async(fn:Function,selector:string)=>{
        Object.assign(script,{element:cardOwner,selector});return runInContext('('+fn.toString()+')(element,selector)',script);
      }})
    })}),
    getByRole:()=>({elementHandle:async()=>({
      waitForElementState:async()=>{assert.equal(button.disabled,false);},
      focus:async()=>{document.activeElement=button;},
      evaluateHandle:async(fn:Function)=>{
        const witness=evaluate<()=>boolean>(fn,button);
        return {evaluate:async(reader:(value:()=>boolean)=>boolean)=>{
          const clicked=reader(witness);
          if(++trustChecks===2 && ['card reload during helper trust','activation dependency reload'].includes(mode))emit('framenavigated',stripe);
          return clicked;
        }};
      },
      press:async(key:string)=>{
        assert.equal(key,'Enter');
        for(const listener of [...button.handlers].sort((a,b)=>Number(b.capture)-Number(a.capture)))
          listener.fn({isTrusted:mode!=='untrusted'});
        if(mode==='untrusted')throw new Error('private untrusted attempt details');
      },
    })}),
    waitForResponse:(predicate:(r:Response)=>boolean)=>{governingResponse=predicate;return found.promise;},
    waitForFunction:async(fn:Function)=>{
      await pendingPresentation;
      for(let n=0;n<25 && !evaluate<boolean>(fn);n++)await new Promise(resolve=>setImmediate(resolve));
      assert.equal(evaluate<boolean>(fn),true);
    },
  };
  const context={
    newPage:async()=>page,on:()=>{},routeWebSocket:async()=>{},
    route:async(pattern:string,fn:typeof routeHandler)=>{assert.equal(pattern,'**/*');routeHandler=fn;},
    newCDPSession:async(surface:unknown)=>{
      if(surface!==page)throw Error('This frame does not have a separate CDP session, it is a part of the parent frame\'s session');
      return {
        on:(name:string,fn:(event:unknown)=>void)=>devtools.set(name,fn),
        send:async(command:string):Promise<unknown>=>command==='Page.getFrameTree'?{frameTree:{frame:{id:'main',url:path},
          childFrames:[...[stripe,sibling,foreign].map(frame=>({frame:{id:frame.name(),url:frame.url(),name:frame.name()}})),
            {frame:{id:'new-iframe',url:'',name:'new-iframe'}}]}}:undefined,
      };
    },
    close:async()=>{
      closed++;
      emit('close');
      if(governingResponse)found.reject(Error('private close details'));
      if(mode==='close fails')throw Error('private close details');
    },
  };
  async function request(target:string,method:string,frame:typeof main|typeof stripe,navigation:boolean,resource:string){
    const id=String(++nextId),frameId=frame===main?'main':frame===uncommitted?'new-iframe':frame.name(),wire={url:target,method};
    const result=Promise.withResolvers<Response>();
    await devtools.get('Network.requestWillBeSent')!({requestId:id,frameId,request:wire});
    const original:unknown={
      url:()=>target,method:()=>method,frame:()=>frame,isNavigationRequest:()=>navigation,resourceType:()=>resource,
      redirectedFrom:()=>null,serviceWorker:()=>null,allHeaders:async()=>{
        if(mode==='easel delayed'&&target===easel){assetPaused.resolve();await resumeAsset.promise;}
        return method==='POST'?{origin:new URL(frame.url()).origin,'content-type':'application/x-www-form-urlencoded'}:
          frame===uncommitted && mode==='uncommitted child cookie'?{cookie:'synthetic cookie'}:{};
      },
      postData:()=>new URLSearchParams({key:picked.publishableKey,client_secret:picked.clientSecret}).toString(),
      response:()=>result.promise,
    };
    const respond=async()=>{
      if(frame===uncommitted)childLoads++;
      if(target===extraScript)extraLoads++;
      if(target===sessionUrl&&mode==='element no response')return;
      if(target.startsWith(url.replace('/confirm','')+'?')){
        if(['unobserved helper read','helper without response'].includes(mode))return;
        if(mode==='late helper response'){
          await new Promise(resolve=>setImmediate(resolve));
          onHelperResponse();
        }
      }
      const redirected=mode==='script redirect' && resource==='script';
      await devtools.get('Fetch.requestPaused')!({requestId:id,networkId:id,frameId,request:wire,responseStatusCode:redirected?302:200});
      if(redirected)return;
      const accepted={
        request:()=>original,url:()=>target,status:()=>200,fromServiceWorker:()=>false,
        body:async()=>Buffer.from(JSON.stringify({object:'setup_intent',id:picked.setupIntentId,
          client_secret:picked.clientSecret,livemode:false,status:'succeeded'})),
      } as Response;
      result.resolve(accepted);
      emit('response',accepted);
      if(method==='POST'){
        writes.push(target);
        if(governingResponse?.(accepted))found.resolve(accepted);
      }
    };
    await routeHandler!({request:()=>original,continue:respond,fallback:respond,abort:async()=>{},
      fulfill:async({body}:{body:string})=>{
        assert.match(target,/^http:\/\/127\.0\.0\.1:\d{4,5}\/owned-trial-setup\//);
        assert.ok(body.includes(`src="${testStripeScript}"`));
        assert.doesNotMatch(body,/seti_|pk_test_|secret_fixture/);await respond();
      }} as unknown as Route);
  }
  registerChromiumProtocolFixture(page);
  const browser={newContext:async()=>context} as unknown as Browser;
  return {browser,picked,writes,url,scriptWitnesses,closed:()=>closed,childLoads:()=>childLoads,extraLoads:()=>extraLoads,run:(timeout=2000)=>runOptionalTrialSetupBrowser(browser,picked,
    {deadline:Date.now()+timeout},()=>false,witness=>{scriptWitnesses.push(witness);})};
}

test('the held document forwards one original charge-free setup POST after one trusted keyboard gesture',async()=>{
  const f=checkout('normal');
  assert.equal(await f.run(),true);
  assert.deepEqual(f.writes,[f.url]);
  assert.equal(f.closed(),1);
  await assert.rejects(f.run(),(error:unknown)=>browserCleanupConfirmed(error) && !String(error).includes(f.picked.clientSecret));
  assert.deepEqual(f.writes,[f.url]);
  assert.equal(f.closed(),1);
});

test('the isolated document waits for the original Payment Element before typing',async()=>{
  const f=checkout('delayed element ready');
  assert.equal(await f.run(),true);
  assert.deepEqual(f.writes,[f.url]);
});

for(const [mode,code] of [['element load error','BROWSER_SETUP_ELEMENT_LOAD_ERROR'],['missing card number','BROWSER_CARD_NUMBER']] as const)
test('setup reports '+mode+' without forwarding a confirmation',async()=>{
  const f=checkout(mode);
  await assert.rejects(f.run(),(error:unknown)=>{
    assert.equal(browserDiagnostic(error,'ACCEPTANCE'),code);
    assert.equal(browserCleanupConfirmed(error),true);
    assert.equal(String(error),'Error: billing_BROWSER');
    return true;
  });
  assert.deepEqual(f.writes,[]);
});

for(const [mode,code] of [
  ['element no session','BROWSER_SETUP_ELEMENT_SESSION_NOT_SENT'],
  ['element no response','BROWSER_SETUP_ELEMENT_SESSION_NO_SUCCESS'],
  ['element no ready event','BROWSER_SETUP_ELEMENT_NO_READY_EVENT'],
] as const)
test('setup diagnoses '+mode+' before any confirmation',async()=>{
  const f=checkout(mode);
  await assert.rejects(f.run(),(error:unknown)=>browserDiagnostic(error,'ACCEPTANCE')===code && browserCleanupConfirmed(error));
  assert.deepEqual(f.writes,[]);
});

test('an owned Stripe.js child GET can load before the child has a document URL',async()=>{
  const f=checkout('uncommitted child');
  assert.equal(await f.run(),true);
  assert.equal(f.childLoads(),1);
  assert.deepEqual(f.writes,[f.url]);
});

for(const mode of ['easel asset','trusted types asset'] as const)
test('the setup document loads the reviewed fingerprinted Stripe.js entry dependency '+mode,async()=>{
  const f=checkout(mode);
  assert.equal(await f.run(),true);
  assert.equal(f.extraLoads(),1);
  assert.deepEqual(f.writes,[f.url]);
});

test('a paused entry dependency loses main-frame authority at confirmation',async()=>{
  const f=checkout('easel delayed');
  await assert.rejects(f.run(),(error:unknown)=>browserDiagnostic(error,'ACCEPTANCE')==='BROWSER_SETUP_ROUTE_FRAME_CHANGED');
  assert.equal(f.extraLoads(),0);
  assert.deepEqual(f.writes,[]);
});

for(const mode of ['easel query','easel bare query','other main asset'] as const)
test('the setup document refuses an unreviewed main-frame script on '+mode,async()=>{
  const f=checkout(mode);
  await assert.rejects(f.run(),(error:unknown)=>browserDiagnostic(error,'ACCEPTANCE')==='BROWSER_SETUP_ROUTE_FRAME_MAIN_SCRIPT');
  assert.equal(f.extraLoads(),0);
  assert.deepEqual(f.writes,[]);
  const name=mode==='other main asset'?'unreviewed':'elements-easel-frame';
  assert.deepEqual(f.scriptWitnesses,[{
    path:'/v3/fingerprinted/js/'+name+'-'+'a'.repeat(32)+'.js',
    queryPresent:mode!=='other main asset', phase:'PRESENTATION',
  }]);
});

for(const [fault,code] of [['cookie','BROWSER_PROVIDER_AUTH'],['foreign parent','BROWSER_SETUP_ROUTE_FRAME_CHILD_NAVIGATION']] as const)
test('an uncommitted Stripe.js child cannot forward a GET with '+fault,async()=>{
  const f=checkout(`uncommitted child ${fault}`);
  await assert.rejects(f.run(),(error:unknown)=>{
    assert.equal(browserDiagnostic(error,'ACCEPTANCE'),code);
    assert.equal(browserCleanupConfirmed(error),true);
    return true;
  });
  assert.equal(f.childLoads(),0);
  assert.deepEqual(f.writes,[]);
});

for(const fault of ['untrusted','foreign frame','repeat','close fails','card reload','sibling confirm','card reload during helper trust'] as const)test('isolated setup stops and reports cleanup for '+fault,async()=>{
  const f=checkout(fault);
  await assert.rejects(f.run(),(error:unknown)=>{
    assert.equal(browserCleanupConfirmed(error),fault!=='close fails');
    const code={untrusted:'BROWSER_CONFIRM_WAIT','foreign frame':'BROWSER_SETUP_ROUTE_FRAME_CONFIRM',
      repeat:'BROWSER_PROVIDER_ROUTE_PAY','close fails':'BROWSER_CLEANUP','card reload':'BROWSER_PROVIDER_ROUTE_PAY',
      'sibling confirm':'BROWSER_PROVIDER_ROUTE_PAY','card reload during helper trust':'BROWSER_PROVIDER_ROUTE_PAY'}[fault];
    assert.equal(browserDiagnostic(error,'ACCEPTANCE'),code);
    assert.equal(String(error),'Error: billing_BROWSER');
    return true;
  });
  assert.deepEqual(f.writes,['repeat','close fails'].includes(fault)?[f.url]:[]);
  assert.equal(f.closed(),1);
});

test('the browser preserves the fixed CDP refusal before the form can submit',async()=>{
  const f=checkout('script redirect');
  await assert.rejects(f.run(),(error:unknown)=>{
    assert.equal(browserDiagnostic(error,'ACCEPTANCE'),'BROWSER_GUARD_RESPONSE_REDIRECT');
    assert.equal(browserCleanupConfirmed(error),true);
    assert.equal(String(error),'Error: billing_BROWSER');
    return true;
  });
  assert.deepEqual(f.writes,[]);
});

for(const shape of ['main transport','approved helper'] as const)test('confirms through the exact prebound '+shape,async()=>{
  const f=checkout(shape);
  assert.equal(await f.run(),true);
  assert.deepEqual(f.writes,[f.url]);
  assert.equal(f.closed(),1);
});

test('an unobserved helper read cannot stall the original card frame',async()=>{
  const f=checkout('unobserved helper read');
  assert.equal(await f.run(),true);
  assert.deepEqual(f.writes,[f.url]);
});

test('a helper without its matched read response cannot forward confirmation',async()=>{
  const f=checkout('helper without response');
  await assert.rejects(f.run(),(error:unknown)=>browserDiagnostic(error,'ACCEPTANCE')==='BROWSER_PROVIDER_ROUTE_PAY');
  assert.deepEqual(f.writes,[]);
});

test('a late helper receipt cannot authorize a confirmation transport',async(t)=>{
  t.mock.timers.enable({apis:['Date'],now:Date.now()});
  const f=checkout('late helper response',()=>t.mock.timers.setTime(Date.now()+15_001));
  await assert.rejects(f.run(45_000),(error:unknown)=>browserDiagnostic(error,'ACCEPTANCE')==='BROWSER_PROVIDER_ROUTE_PAY');
  assert.deepEqual(f.writes,[]);
});

test('the exact reviewed Stripe checker can load during the trusted confirmation',async()=>{
  const f=checkout('activation dependency');
  assert.equal(await f.run(),true);
  assert.equal(f.extraLoads(),1);
  assert.deepEqual(f.writes,[f.url]);
});

for(const mode of ['activation dependency query','activation alternate fingerprint'] as const)
test('the activated setup still refuses '+mode,async()=>{
  const f=checkout(mode);
  await assert.rejects(f.run(),(error:unknown)=>browserDiagnostic(error,'ACCEPTANCE')==='BROWSER_SETUP_ROUTE_FRAME_MAIN_SCRIPT');
  assert.equal(f.extraLoads(),0);
  assert.deepEqual(f.writes,[]);
});

test('a card reload invalidates the checker request before forwarding',async()=>{
  const f=checkout('activation dependency reload');
  await assert.rejects(f.run(),(error:unknown)=>browserDiagnostic(error,'ACCEPTANCE')==='BROWSER_PROVIDER_ROUTE_PAY');
  assert.equal(f.extraLoads(),0);
  assert.deepEqual(f.writes,[]);
});
