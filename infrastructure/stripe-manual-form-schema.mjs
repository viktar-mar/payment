import {validateFormDiagnosticSample,classifyFormDiagnosticSample} from './stripe-form-diagnostic-schema.mjs';
const KEYS=['version','mode','observation','end','sample','no_confirmation','gateway_closed','runtime_closed','browser_closure','provenance'];
const PROOFS=['no_confirmation','gateway_closed','runtime_closed'];
const OVERRIDES=['ABORTED','ACTIVE_DEADLINE','FAILED'];
const encoder=new TextEncoder();
function exact(value){
  try{
    if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))return null;
    const descriptors=Object.getOwnPropertyDescriptors(value),names=Reflect.ownKeys(descriptors);
    if(names.length!==KEYS.length||names.some(key=>typeof key!=='string'||!KEYS.includes(key)))return null;
    const copy={};for(const key of KEYS){const d=descriptors[key];if(!d||!d.enumerable||!Object.hasOwn(d,'value'))return null;copy[key]=d.value;}
    return copy;
  }catch{return null;}
}
/** Browser provenance only; historical ready is allowed with a dominant failed end. */
export function validateManualFormReport(value){
  const r=exact(value);
  if(!r||r.version!==1||r.mode!=='MANUAL_FORM_DIAGNOSTIC'||r.browser_closure!=='NOT_OWNED'||r.provenance!=='BROWSER_REPORTED'
    ||PROOFS.some(key=>typeof r[key]!=='boolean'))return null;
  const s=r.sample===null?null:validateFormDiagnosticSample(r.sample);
  if(r.sample!==null&&!s)return null;r.sample=s;
  const classification=s?classifyFormDiagnosticSample(s):null;
  let ends;
  switch(r.observation){
    case 'NOT_STARTED':if(s)return null;ends=['NOT_STARTED',...OVERRIDES];break;
    case 'NO_REPORT':if(s)return null;ends=['LOCAL_LIMIT',...OVERRIDES];break;
    case 'INVALID_REPORT':if(s)return null;ends=['FAILED'];break;
    case 'READY_REPORTED':
      if(!classification?.startsWith('FORM_READY_')||s.bucket==='AT_LIMIT')return null;
      ends=['READY_REPORTED',...OVERRIDES];break;
    case 'PENDING_REPORTED':
      if(!s||!['AFTER_15S','AT_LIMIT'].includes(s.bucket)||!(classification.startsWith('WAITING_')
        ||['ELEMENT_SETUP_INCOMPLETE','WIDGET_HANDOFF_INCOMPLETE'].includes(classification)))return null;
      ends=['LOCAL_LIMIT',...OVERRIDES];break;
    case 'TERMINAL_REPORTED':
      if(!['ELEMENT_LOADERROR','OPERATION_FAILED','ABORTED','BINDING_LOST','CONFIRMATION_BLOCKED'].includes(classification))return null;
      ends=['FAILED'];break;
    default:return null;
  }
  if(!ends.includes(r.end)||(r.end==='READY_REPORTED'&&!PROOFS.every(key=>r[key]===true)))return null;
  if(encoder.encode(JSON.stringify(r)+'\n').byteLength>4096)return null;
  return Object.freeze(r);
}
export function formatManualFormReport(value){
  const report=validateManualFormReport(value);return report?JSON.stringify(report)+'\n':null;
}
export function parseManualFormReport(wire){
  if(typeof wire!=='string'||!wire.endsWith('\n')||wire.slice(0,-1).includes('\n')||encoder.encode(wire).byteLength>4096)return null;
  try{
    const report=validateManualFormReport(JSON.parse(wire));if(!report)return null;
    // Finite decoded keys and values contain no colons; count nested sample keys too.
    if((wire.match(/:/g)??[]).length!==KEYS.length+(report.sample?Object.keys(report.sample).length:0))return null;
    return report;
  }catch{return null;}
}
