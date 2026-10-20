const PORTS=['backend','demo','receiver','postgres','mailpitHttp','mailpitSmtp'];
const EXHAUSTED=new Set(['2f3f49b1-df07-41b1-b978-bc884b390813','6f684b69-32e6-429f-a34c-e4f69ba2ce18']);
const FLAGS=['PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER','PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND','PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT','PAYMENT_STRIPE_SANDBOX_ENABLED','PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY',
  'PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_FULL_RUN','PAYMENT_STRIPE_SANDBOX_OWNED_RUN','PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS',
  'DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'];
function record(value) {
  if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw null;
  return Object.getOwnPropertyDescriptors(value);
}
function data(descriptors,key) {
  const descriptor=descriptors[key];
  if(!descriptor||!Object.hasOwn(descriptor,'value')||!descriptor.enumerable)throw null;
  return descriptor.value;
}
function envSnapshot(env) {
  if(!env||typeof env!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(env)))throw null;
  const selected={};
  for(const key of FLAGS){
    const descriptor=Object.getOwnPropertyDescriptor(env,key);
    if(descriptor&&(!Object.hasOwn(descriptor,'value')||!descriptor.enumerable))throw null;
    selected[key]=descriptor?.value;
  }
  return selected;
}
// Only allowlisted data properties are read. Accessors/private environment values remain inert.
export function manualFormOptIn(env) {
  try {
    const flags=envSnapshot(env);
    return flags.PAYMENT_STRIPE_SANDBOX_ENABLED==='true'&&flags.PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC==='true'
      &&flags.PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY==='true'
      &&['PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER','PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND','PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT','PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_FULL_RUN','PAYMENT_STRIPE_SANDBOX_OWNED_RUN','PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS']
        .every(key=>[undefined,'','false'].includes(flags[key]))
      &&[undefined,''].includes(flags.DEBUG)&&[undefined,''].includes(flags.NODE_DEBUG)
      &&[undefined,'','0'].includes(flags.PWDEBUG)&&[undefined,'','off'].includes(flags.PW_TRACE);
  }catch{return false;}
}

/** Shared inert source/daemon/six-port rules for credential-free preflight and manual admission. */
export function validateFormDiagnosticTopology(value) {
  try {
    const {runId,expectedSource,daemon,ports}=value;
    if(typeof runId!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(runId)||EXHAUSTED.has(runId)
      ||typeof expectedSource!=='string'||!/^[0-9a-f]{40}$/.test(expectedSource))return null;
    const descriptors=record(ports);
    if(Reflect.ownKeys(descriptors).length!==6||PORTS.some(key=>!Object.hasOwn(descriptors,key)))return null;
    const selected=Object.fromEntries(PORTS.map(key=>[key,data(descriptors,key)]));
    if(Object.values(selected).some(port=>!Number.isInteger(port)||port<1024||port>65535)||new Set(Object.values(selected)).size!==6)return null;
    if(typeof daemon!=='string'||/[\s\x00-\x1f\x7f%]/.test(daemon))return null;
    const url=new URL(daemon);
    if(url.protocol!=='unix:'||url.host||url.username||url.password||url.search||url.hash||url.pathname.length<2||url.href!==daemon||daemon!=='unix://'+url.pathname)return null;
    return Object.freeze({runId,expectedSource,daemon,ports:Object.freeze(selected)});
  }catch{return null;}
}

export function validateManualFormOptions(value) {
  try {
    const descriptors=record(value),env=data(descriptors,'env');if(!manualFormOptIn(env))return null;
    const keys=['env','runId','expectedSource','daemon','ports','manualPort'];
    if(Reflect.ownKeys(descriptors).length!==keys.length||keys.some(key=>!Object.hasOwn(descriptors,key)))return null;
    const input=Object.fromEntries(keys.map(key=>[key,data(descriptors,key)])),base=validateFormDiagnosticTopology(input),manualPort=input.manualPort;
    if(!base||!Number.isInteger(manualPort)||manualPort<1024||manualPort>65535||Object.values(base.ports).includes(manualPort))return null;
    // An immutable trusted flag set is sufficient after all caller flags have been checked.
    return Object.freeze({...base,env:Object.freeze({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}),manualPort});
  }catch{return null;}
}
