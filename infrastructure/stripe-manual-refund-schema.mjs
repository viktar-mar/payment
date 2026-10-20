const PROOFS=['target_verified','callback_rotated','partial_confirmed','remainder_confirmed','provider_verified','webhook_verified','journal_verified','callback_verified','listener_closed','runtime_closed'];
const KEYS=['version','mode','result','phase',...PROOFS,'provenance'];
const PHASES=['ADMISSION','RESTORE','BINDING','LISTENER','BASELINE','ROTATION','PARTIAL','REMAINDER','VERIFY','CLEANUP'];
const STAGES=['BASELINE','ROTATION','DISPATCH','ACKNOWLEDGEMENT','DETAIL','CALLBACK','PROVIDER','CONFIRMATION','FINAL','CLEANUP'];
const REASONS=['INTEGRITY','UNCONFIRMED','EXHAUSTED','ABORTED','DEADLINE'];
/** A copied finite projection; never inspect exceptions or evaluate accessors. */
export function validateManualRefundDiagnostic(value){
  try{
    if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))return null;
    const d=Object.getOwnPropertyDescriptors(value),keys=['stage','reason','probes'];
    if(Reflect.ownKeys(d).length!==keys.length||keys.some(k=>!d[k]?.enumerable||!Object.hasOwn(d[k],'value')))return null;
    const stage=d.stage.value,reason=d.reason.value,probes=d.probes.value;
    if(!STAGES.includes(stage)||!REASONS.includes(reason)||!Number.isInteger(probes)||probes<0||probes>30)return null;
    return Object.freeze({stage,reason,probes});
  }catch{return null;}
}
/** False records unverified evidence; it never establishes absence of an external effect. */
export function validateManualRefundReport(value){
  try{if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))return null;const d=Object.getOwnPropertyDescriptors(value),keys=Reflect.ownKeys(d);if(keys.length!==KEYS.length+(Object.hasOwn(d,'diagnostic')?1:0)||keys.some(k=>!KEYS.includes(k)&&k!=='diagnostic'))return null;
    const r={};for(const key of KEYS){if(!d[key].enumerable||!Object.hasOwn(d[key],'value'))return null;r[key]=d[key].value;}
    if(r.version!==1||r.mode!=='MANUAL_REFUND'||!['FAILED','PENDING','SUCCEEDED'].includes(r.result)||!PHASES.includes(r.phase)||r.provenance!=='CONTROLLER_REPORTED'||PROOFS.some(k=>typeof r[k]!=='boolean'))return null;
    if(r.result==='SUCCEEDED'&&(!PROOFS.every(k=>r[k])||!['VERIFY','CLEANUP'].includes(r.phase)))return null;
    if(r.callback_rotated&&!r.target_verified||r.partial_confirmed&&!r.callback_rotated||r.remainder_confirmed&&!r.partial_confirmed||r.phase==='REMAINDER'&&!r.partial_confirmed)return null;
    if((r.partial_confirmed||r.remainder_confirmed)&&!['provider_verified','webhook_verified','journal_verified','callback_verified'].every(k=>r[k]))return null;
    if(r.result==='FAILED'&&(r.callback_rotated||r.partial_confirmed||r.remainder_confirmed))return null;
    if(Object.hasOwn(d,'diagnostic')){
      if(!d.diagnostic.enumerable||!Object.hasOwn(d.diagnostic,'value')||r.result==='SUCCEEDED')return null;
      const diagnostic=validateManualRefundDiagnostic(d.diagnostic.value);if(!diagnostic)return null;r.diagnostic=diagnostic;
    }
    return Buffer.byteLength(JSON.stringify(r)+'\n')<=4096?Object.freeze(r):null;
  }catch{return null;}
}
export function formatManualRefundReport(value){const r=validateManualRefundReport(value);return r?JSON.stringify(r)+'\n':null;}
