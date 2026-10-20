import {validateManualRefundDiagnostic} from './stripe-manual-refund-schema.mjs';

const PROOFS=['target_verified','prior_partial_verified','prior_callback_delivery_verified','callback_rotated','remainder_confirmed','provider_verified','webhook_verified','journal_verified','callback_signature_verified','final_total_verified','listener_closed','runtime_closed'];
const KEYS=['version','mode','result','phase',...PROOFS,'provenance'];
const PHASES=['ADMISSION','RESTORE','BINDING','LISTENER','BASELINE','ROTATION','REMAINDER','VERIFY','CLEANUP'];

/** Copies a finite projection. False means unverified, never no external effect. */
export function validateManualRefundRemainderReport(value){
  try{
    if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))return null;
    const descriptors=Object.getOwnPropertyDescriptors(value),keys=Reflect.ownKeys(descriptors);
    if(keys.length!==KEYS.length+(Object.hasOwn(descriptors,'diagnostic')?1:0)||keys.some(key=>!KEYS.includes(key)&&key!=='diagnostic'))return null;
    const report={};
    for(const key of KEYS){const d=descriptors[key];if(!d?.enumerable||!Object.hasOwn(d,'value'))return null;report[key]=d.value;}
    if(report.version!==1||report.mode!=='MANUAL_REFUND_REMAINDER'||!['FAILED','PENDING','SUCCEEDED'].includes(report.result)||!PHASES.includes(report.phase)||report.provenance!=='CONTROLLER_REPORTED'||PROOFS.some(key=>typeof report[key]!=='boolean'))return null;
    if(report.prior_partial_verified&&!report.target_verified||report.prior_callback_delivery_verified&&!report.prior_partial_verified)return null;
    if(report.callback_rotated&&!['target_verified','prior_partial_verified','prior_callback_delivery_verified'].every(key=>report[key]))return null;
    if(report.remainder_confirmed&&!['callback_rotated','provider_verified','webhook_verified','journal_verified','callback_signature_verified'].every(key=>report[key]))return null;
    if(report.final_total_verified&&!report.remainder_confirmed||report.phase==='REMAINDER'&&!report.callback_rotated)return null;
    if(report.result==='FAILED'&&(report.phase!=='ADMISSION'||PROOFS.filter(key=>!key.endsWith('_closed')).some(key=>report[key])))return null;
    if(report.result==='SUCCEEDED'&&(!PROOFS.every(key=>report[key])||!['VERIFY','CLEANUP'].includes(report.phase)))return null;
    if(Object.hasOwn(descriptors,'diagnostic')){
      const d=descriptors.diagnostic;if(!d.enumerable||!Object.hasOwn(d,'value')||report.result==='SUCCEEDED')return null;
      const diagnostic=validateManualRefundDiagnostic(d.value);if(!diagnostic)return null;report.diagnostic=diagnostic;
    }
    return Buffer.byteLength(JSON.stringify(report)+'\n')<=4096?Object.freeze(report):null;
  }catch{return null;}
}
export function formatManualRefundRemainderReport(value){const report=validateManualRefundRemainderReport(value);return report?JSON.stringify(report)+'\n':null;}
