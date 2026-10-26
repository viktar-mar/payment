const PROOFS=['confirmation_granted','payment_confirmed','provider_confirmed','webhook_verified','journal_verified','callback_verified','gateway_closed','listener_closed','runtime_closed'];
const KEYS=['version','mode','result','phase',...PROOFS,'browser_closure','provenance'];
const PHASES=['ADMISSION','PREPARE','AUTHORIZATION','BINDING','LISTENER','START','PROVISION','HANDOFF','AWAITING_PAY','VERIFY','SUCCEEDED','CLEANUP'];
/** Finite controller evidence. False is unverified, never proof of absence. */
export function validateManualPaymentReport(value){
  try{
    if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))return null;
    const d=Object.getOwnPropertyDescriptors(value),keys=Reflect.ownKeys(d),threeDSecure=d.mode?.value==='MANUAL_3DS',expected=threeDSecure?[...KEYS,'three_ds_verified']:KEYS;
    if(keys.length!==expected.length||keys.some(key=>typeof key!=='string'||!expected.includes(key)))return null;
    const r={};for(const key of expected){if(!d[key]?.enumerable||!Object.hasOwn(d[key],'value'))return null;r[key]=d[key].value;}
    if(r.version!==1||!['MANUAL_PAYMENT','MANUAL_3DS'].includes(r.mode)||!['SUCCEEDED','NOT_STARTED','FAILED','PENDING'].includes(r.result)||!PHASES.includes(r.phase)
      ||r.browser_closure!=='NOT_OWNED'||r.provenance!=='CONTROLLER_REPORTED'||PROOFS.some(key=>typeof r[key]!=='boolean'))return null;
    if(r.result==='SUCCEEDED'&&(r.phase!=='SUCCEEDED'||!PROOFS.every(key=>r[key])))return null;
    if(r.phase==='SUCCEEDED'&&r.result!=='SUCCEEDED')return null;
    if(['NOT_STARTED','FAILED'].includes(r.result)&&PROOFS.slice(0,6).some(key=>r[key]))return null;
    if(r.result==='NOT_STARTED'&&(!PROOFS.slice(6).every(key=>r[key])||!['VERIFY','CLEANUP'].includes(r.phase)))return null;
    if(PROOFS.slice(1,6).some(key=>r[key])&&!r.confirmation_granted)return null;
    if(threeDSecure&&(typeof r.three_ds_verified!=='boolean'||r.result==='SUCCEEDED'&&!r.three_ds_verified||r.three_ds_verified&&!PROOFS.slice(0,6).every(key=>r[key])))return null;
    if(Buffer.byteLength(JSON.stringify(r)+'\n')>4096)return null;return Object.freeze(r);
  }catch{return null;}
}
export function formatManualPaymentReport(value){const r=validateManualPaymentReport(value);return r?JSON.stringify(r)+'\n':null;}
export function parseManualPaymentReport(wire){
  if(typeof wire!=='string'||!wire.endsWith('\n')||wire.slice(0,-1).includes('\n')||Buffer.byteLength(wire)>4096)return null;
  try{const r=validateManualPaymentReport(JSON.parse(wire));if(!r||(wire.match(/:/g)??[]).length!==Object.keys(r).length)return null;return r;}catch{return null;}
}
