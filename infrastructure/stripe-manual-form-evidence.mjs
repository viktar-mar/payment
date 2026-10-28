/** Pure finite browser-reported evidence; no provider or node capabilities. */
import {validateFormDiagnosticSample} from './stripe-form-diagnostic-schema.mjs';
const DOMAINS={
  loaderstart_seen:[false,true],host_box:['ABSENT','ZERO_AREA','POSITIVE_AREA','UNAVAILABLE'],
  frame_box:['ABSENT','ZERO_AREA','POSITIVE_AREA','MULTIPLE','UNAVAILABLE'],
  host_visibility:['ABSENT','VISIBLE','HIDDEN','UNAVAILABLE'],frame_visibility:['ABSENT','VISIBLE','HIDDEN','UNAVAILABLE'],
  frame_load:['NO_FRAME','NOT_SEEN','SEEN','UNAVAILABLE'],csp_observation:['OBSERVED','UNAVAILABLE'],
};
const KEYS=[...Object.keys(DOMAINS),'csp_enforced_mask','csp_report_only_mask'];
const encoder=new TextEncoder();
function exact(value,keys){
  try{
    if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))return null;
    const descriptors=Object.getOwnPropertyDescriptors(value),names=Reflect.ownKeys(descriptors);
    if(names.length!==keys.length||names.some(key=>typeof key!=='string'||!keys.includes(key)))return null;
    const copy={};for(const key of keys){const d=descriptors[key];if(!d?.enumerable||!Object.hasOwn(d,'value'))return null;copy[key]=d.value;}return copy;
  }catch{return null;}
}
export function unavailableManualFormEvidence(){
  return Object.freeze({loaderstart_seen:false,host_box:'UNAVAILABLE',frame_box:'UNAVAILABLE',host_visibility:'UNAVAILABLE',
    frame_visibility:'UNAVAILABLE',frame_load:'UNAVAILABLE',csp_observation:'UNAVAILABLE',csp_enforced_mask:0,csp_report_only_mask:0});
}
export function validateManualFormEvidence(value){
  const e=exact(value,KEYS);
  if(!e||Object.entries(DOMAINS).some(([key,values])=>!values.includes(e[key])))return null;
  for(const key of ['csp_enforced_mask','csp_report_only_mask'])if(!Number.isInteger(e[key])||e[key]<0||e[key]>15)return null;
  if(e.csp_observation==='UNAVAILABLE'&&(e.csp_enforced_mask!==0||e.csp_report_only_mask!==0))return null;
  if(e.frame_box==='MULTIPLE'&&(e.frame_visibility!=='UNAVAILABLE'||e.frame_load!=='UNAVAILABLE'))return null;
  return Object.freeze(e);
}
export function validateManualFormObservation(value){
  const o=exact(value,['version','sample','evidence']);if(!o||o.version!==2)return null;
  const sample=validateFormDiagnosticSample(o.sample),evidence=validateManualFormEvidence(o.evidence);
  return sample&&evidence?Object.freeze({version:2,sample,evidence}):null;
}
export function parseManualFormObservation(wire){
  if(typeof wire!=='string'||encoder.encode(wire).byteLength>4096)return null;
  try{
    const o=validateManualFormObservation(JSON.parse(wire));
    // Every accepted scalar/key is finite and contains no colon. Each extra
    // JSON member (including an escaped duplicate) adds a structural colon.
    return o&&(wire.match(/:/g)??[]).length===3+Object.keys(o.sample).length+KEYS.length?o:null;
  }catch{return null;}
}
