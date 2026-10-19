import {unavailableManualFormEvidence} from '../../../infrastructure/stripe-manual-form-evidence.mjs';
import type {ManualFormReadinessEvidence} from '../../../infrastructure/stripe-manual-form-evidence.mjs';
import {ownedProviderHost} from '../../../frontend/sdk/src/widget/provider-mount';

interface Options {
  readonly getWidget:()=>HTMLElement|undefined;
  readonly isActive:()=>boolean;
  readonly readinessSnapshot:()=>Readonly<{loaderstart_seen:boolean}>|undefined;
}

/** Layout/style categories describe only the owned host and outer iframe box.
 * They cannot establish what a cross-origin frame rendered. */
export function createManualFormReadinessObserver(options:Options):{
  snapshot():Readonly<ManualFormReadinessEvidence>;close():void;
}{
  let closed=false,root:Document|undefined,current:Element|undefined,loaded=false;
  let csp:ManualFormReadinessEvidence['csp_observation']='UNAVAILABLE',enforced=0,reportOnly=0;
  let last=unavailableManualFormEvidence();
  const active=():boolean=>{try{return !closed&&options.isActive();}catch{return false;}};
  function close():void{
    if(closed)return;closed=true;
    document.removeEventListener('securitypolicyviolation',onCsp);
    root?.removeEventListener('load',onLoad,true);root=undefined;current=undefined;
  }
  function onCsp(event:SecurityPolicyViolationEvent):void{
    if(!active()){close();return;}
    try{
      if(!event.isTrusted)return;
      const directive=event.effectiveDirective,disposition=event.disposition;
      if(typeof directive!=='string'||!['enforce','report'].includes(disposition))return;
      const mask=directive.startsWith('script-src')?1:['frame-src','child-src'].includes(directive)?2:directive==='connect-src'?4:8;
      if(disposition==='enforce')enforced|=mask;else reportOnly|=mask;
      snapshot();
    }catch{/* Event detail is never inspected or retained. */}
  }
  function identify():{host:Element|null;frames:NodeListOf<HTMLIFrameElement>|undefined}{
    const widget=options.getWidget(),next=widget?.ownerDocument??document;
    if(root!==next){root?.removeEventListener('load',onLoad,true);root=next;root?.addEventListener('load',onLoad,true);current=undefined;loaded=false;}
    const host=widget?ownedProviderHost(widget)??null:null,frames=host?.querySelectorAll('iframe');
    const frame=frames?.length===1?frames[0]:undefined;
    if(current!==frame){current=frame;loaded=false;}
    return {host,frames};
  }
  function onLoad(event:Event):void{
    if(!active()){close();return;}
    try{if(!event.isTrusted)return;identify();if(current&&event.target===current){loaded=true;snapshot();}}catch{/* Unknown is not a load failure. */}
  }
  function box(node:Element|null):ManualFormReadinessEvidence['host_box']{
    if(!node)return 'ABSENT';
    try{const {width,height}=node.getBoundingClientRect();return !Number.isFinite(width)||!Number.isFinite(height)||width<0||height<0?'UNAVAILABLE':width>0&&height>0?'POSITIVE_AREA':'ZERO_AREA';}catch{return 'UNAVAILABLE';}
  }
  function visibility(node:Element|null):ManualFormReadinessEvidence['host_visibility']{
    if(!node)return 'ABSENT';
    try{
      for(let current:Element|null=node;current;){
        const style=getComputedStyle(current);
        if(current.hasAttribute('hidden')||style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||style.opacity==='0')return 'HIDDEN';
        const tree=current.getRootNode();current=current.assignedSlot??current.parentElement??(tree instanceof ShadowRoot?tree.host:null);
      }
      return node.isConnected?'VISIBLE':'UNAVAILABLE';
    }catch{return 'UNAVAILABLE';}
  }
  function snapshot():Readonly<ManualFormReadinessEvidence>{
    if(!active()){close();return last;}
    let loaderstart=false;try{loaderstart=options.readinessSnapshot()?.loaderstart_seen===true;}catch{/* Remains unknown/false. */}
    let result:ManualFormReadinessEvidence={...unavailableManualFormEvidence(),loaderstart_seen:loaderstart,csp_observation:csp,csp_enforced_mask:enforced,csp_report_only_mask:reportOnly};
    try{
      const {host,frames}=identify(),multiple=!!frames&&frames.length>1,frame=frames?.length===1?frames[0]!:null;
      result={...result,host_box:box(host),host_visibility:visibility(host),frame_box:multiple?'MULTIPLE':box(frame),
        frame_visibility:multiple?'UNAVAILABLE':visibility(frame),frame_load:multiple?'UNAVAILABLE':!frame?'NO_FRAME':loaded?'SEEN':'NOT_SEEN'};
    }catch{/* Preserve finite unavailable geometry and CSP categories. */}
    last=Object.freeze(result);return last;
  }
  if(active())try{document.addEventListener('securitypolicyviolation',onCsp);csp='OBSERVED';}catch{/* No CSP claim without a listener. */}
  return Object.freeze({snapshot,close});
}
