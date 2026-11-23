// @ts-ignore Explicit source extension also supports the dependency-free Node runner.
import {loadStripeClient} from '../../../frontend/sdk/src/providers/stripe-loader.ts';

type State='IDLE'|'LOADING'|'READY'|'ERROR'|'STOPPED'|'TIMEOUT';
type Profile='recovery-presentation'|'recovery-submit';
interface ElementPort {
  on(name:'ready'|'loaderror',listener:()=>void):unknown;
  on(name:'change',listener:(event:{elementType?:unknown;empty?:unknown;complete?:unknown})=>void):unknown;
  mount(host:HTMLElement):unknown;
  destroy():void;
}
interface Ports {
  host:HTMLElement;
  load:(key:string,profile?:Profile)=>Promise<unknown>;
  status:(state:State)=>void;
  onPageLoss:(listener:()=>void)=>()=>void;
  deadline:(listener:()=>void)=>()=>void;
}
const testKey=(key:unknown):key is string=>typeof key==='string'&&/^pk_test_[A-Za-z0-9]+$/.test(key);

function submitResult(result:unknown){
  if(!result||typeof result!=='object'||Object.getPrototypeOf(result)!==Object.prototype)return 'SUBMIT_ERROR';
  for(const key of Reflect.ownKeys(result))
    if((key!=='error'&&key!=='selectedPaymentMethod')||!Object.hasOwn(Object.getOwnPropertyDescriptor(result,key)!,'value'))return 'SUBMIT_ERROR';
  const error=Object.getOwnPropertyDescriptor(result,'error')?.value;
  const method=Object.getOwnPropertyDescriptor(result,'selectedPaymentMethod')?.value;
  if(error!==undefined)return error&&typeof error==='object'&&!Array.isArray(error)&&method===undefined?'SUBMIT_VALIDATION':'SUBMIT_ERROR';
  return method===undefined||method==='card'?'SUBMIT_OK':'SUBMIT_ERROR';
}

/** Private page controller: never receives an operation, secret, or financial method. */
export function createLoadController(ports:Ports){
  let state:State='IDLE',element:ElementPort|undefined;
  let holdReady=false;
  let complete=false,submitInput:(()=>Promise<{error?:unknown}>)|undefined;
  let removeLoss=()=>{},cancelDeadline=()=>{};
  function finish(next:State){
    if(state!=='LOADING'&&!(state==='READY'&&holdReady)&&!(state==='IDLE'&&next==='STOPPED'))return;
    state=next;
    try{element?.destroy();}catch{state='ERROR';}finally{element=undefined;submitInput=undefined;complete=false;removeLoss();cancelDeadline();ports.status(state);}
  }
  return Object.freeze({
    state:()=>state,
    stop:()=>finish('STOPPED'),
    inputComplete:()=>state==='READY'&&complete&&submitInput!==undefined,
    async submit(expires:number){
      if(!Number.isSafeInteger(expires)||Date.now()>=expires)return 'SUBMIT_ERROR';
      if(state!=='READY'||!complete||!submitInput)return 'INCOMPLETE';
      const validate=submitInput;submitInput=undefined;
      try{
        const result=await validate();
        return state==='READY'&&Date.now()<expires?submitResult(result):'SUBMIT_ERROR';
      }catch{return 'SUBMIT_ERROR';}
    },
    async configure(key:unknown,profile?:unknown){
      if(!testKey(key)||(profile!==undefined&&profile!=='recovery-presentation'&&profile!=='recovery-submit')||
        state!=='IDLE'||!ports.host.isConnected)throw Error('CONFIGURATION');
      const verifyInput=profile==='recovery-submit';
      holdReady=profile!==undefined;
      state='LOADING';ports.status(state);
      ports.host.inert=!verifyInput;ports.host.tabIndex=-1;ports.host.style.pointerEvents=verifyInput?'':'none';
      removeLoss=ports.onPageLoss(()=>finish('STOPPED'));
      cancelDeadline=ports.deadline(()=>finish('TIMEOUT'));
      try{
        const stripe=await ports.load(key,profile) as {elements(options:unknown):{
          create(type:'payment',options:unknown):ElementPort;submit():Promise<{error?:unknown}>}}|null;
        if(state!=='LOADING')return;
        if(!stripe||!ports.host.isConnected){finish('ERROR');return;}
        const elements=stripe.elements({mode:holdReady?'subscription':'payment',amount:2000,currency:'eur',
          allowedPaymentMethodTypes:['card'],...(holdReady?{locale:'en'}:{})});
        const mounted=elements.create('payment',{wallets:{applePay:'never',googlePay:'never',...(holdReady?{link:'never'}:{})}});
        element=mounted;
        if(verifyInput){
          submitInput=()=>elements.submit();
          mounted.on('change',event=>{
            if(state==='LOADING'||state==='READY')complete=event?.elementType==='payment'&&event.empty===false&&event.complete===true;
          });
        }
        let mountReturned=false, sawReady=false;
        const reportReady=()=>{
          if(state!=='LOADING')return;
          sawReady=true;
          if(!mountReturned)return;
          if(holdReady){state='READY';ports.status(state);}
          else finish('READY');
        };
        mounted.on('loaderror',()=>finish('ERROR'));
        if(state!=='LOADING')return;
        mounted.on('ready',reportReady);
        if(state!=='LOADING')return;
        mounted.mount(ports.host);
        mountReturned=true;
        if(sawReady)reportReady();
      }catch{finish('ERROR');}
    },
  });
}

async function loadForBrowserCheck(key:string,profile?:Profile) {
  if(profile!=='recovery-presentation'){
    const {loadStripe} = await import('@stripe/stripe-js/pure/index.js');
    // The probe bundle resolves both imports to the same pure loader instance.
    loadStripe.setLoadParameters({advancedFraudSignals:false});
  }
  return loadStripeClient(key);
}

// No loader or provider acquisition until the launcher explicitly configures this page.
if(typeof document!=='undefined'){
  const host=document.getElementById('provider-host');
  const output=document.getElementById('status');
  if(host&&output){
    const controller=createLoadController({host,load:loadForBrowserCheck,status:state=>{output.textContent=state;},
      onPageLoss:listener=>{window.addEventListener('pagehide',listener,{once:true});return ()=>window.removeEventListener('pagehide',listener);},
      deadline:listener=>{const timer=setTimeout(listener,60000);return ()=>clearTimeout(timer);},
    });
    Object.defineProperty(window,'configureStripeBrowserLoadCheck',{value:(key:unknown,profile?:unknown)=>controller.configure(key,profile),writable:false,configurable:false});
    Object.defineProperty(window,'stripeBrowserProbeInputComplete',{value:controller.inputComplete,writable:false,configurable:false});
    Object.defineProperty(window,'stripeBrowserProbeSubmit',{value:controller.submit,writable:false,configurable:false});
  }
}
