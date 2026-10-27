const PREFIX='PAYMENT_STRIPE_SANDBOX_';
const REQUIRED=['ENABLED','ALLOW_TEST_OPERATIONS','OWNED_RUN','REMAINING_ONLY'].map(key=>PREFIX+key);
const EXCLUDED=['MANUAL_REFUND','MANUAL_REFUND_REMAINDER','FULL_RUN','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT'].map(key=>PREFIX+key);
const DEBUG=['DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'];
/** Capture own data only; no remaining-mode selector is forwarded to underlying capabilities. */
export function remainingOnlyEnvironment(env) {
  try {
    if(!env||typeof env!=='object')return null;
    const descriptors=Object.getOwnPropertyDescriptors(env),values=Object.create(null);
    for(const key of [...REQUIRED,...EXCLUDED,...DEBUG]) {
      const descriptor=descriptors[key];
      if(descriptor? !descriptor.enumerable||!Object.hasOwn(descriptor,'value') : key in env)return null;
      values[key]=descriptor?.value;
    }
    if(!REQUIRED.every(key=>values[key]==='true')||!EXCLUDED.every(key=>[undefined,'','false'].includes(values[key]))
      ||![undefined,''].includes(values.DEBUG)||![undefined,''].includes(values.NODE_DEBUG)
      ||![undefined,'','0'].includes(values.PWDEBUG)||![undefined,'','off'].includes(values.PW_TRACE))return null;
    return Object.freeze({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true',
      DEBUG:values.DEBUG,NODE_DEBUG:values.NODE_DEBUG,PWDEBUG:values.PWDEBUG});
  }catch{return null;}
}
export const remainingOnlyOptIn=env=>remainingOnlyEnvironment(env)!==null;
/** Preserve legacy exotic containers without reading a selected or malformed selector. */
export function remainingOnlyExcluded(env) {
  try {
    if(!env||typeof env!=='object')return false;
    const key=PREFIX+'REMAINING_ONLY',descriptor=Object.getOwnPropertyDescriptor(env,key);
    return descriptor?descriptor.enumerable&&Object.hasOwn(descriptor,'value')&&[undefined,'','false'].includes(descriptor.value):!(key in env);
  }catch{return false;}
}
