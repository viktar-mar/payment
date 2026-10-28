const CSP_MASKS=['csp_enforced_mask','csp_report_only_mask'];
const RESOURCE_MASKS=['resource_transport_failure_mask','resource_http_4xx_mask','resource_http_5xx_mask'];
const KEYS=['csp_observation',...CSP_MASKS,'resource_observation',...RESOURCE_MASKS,'fetch_failure_destination','fetch_failure_code','environment_freshness'];
const DESTINATIONS=['OWN_APP','STRIPE_API','STRIPE_JS','OTHER','UNKNOWN'];
const CODES=['ABORTED','NAME_NOT_RESOLVED','CONNECTION_REFUSED','CONNECTION_RESET','TIMED_OUT','CERTIFICATE','BLOCKED_BY_CLIENT','GENERIC_FAILED','UNKNOWN'];

/** Copy only exact, own enumerable scalar data; never invoke a payload getter. */
export function validateFormEnvironmentEvidence(value) {
  try {
    if(value===null||typeof value!=='object')return null;
    const prototype=Object.getPrototypeOf(value);
    if(prototype!==Object.prototype&&prototype!==null)return null;
    const descriptors=Object.getOwnPropertyDescriptors(value),names=Reflect.ownKeys(descriptors);
    if(names.length!==KEYS.length||names.some(key=>typeof key!=='string'||!KEYS.includes(key)))return null;
    const copy={};
    for(const key of KEYS) {
      const descriptor=descriptors[key];
      if(!descriptor||!Object.hasOwn(descriptor,'value')||!descriptor.enumerable)return null;
      copy[key]=descriptor.value;
    }
    for(const key of [...CSP_MASKS,...RESOURCE_MASKS])if(!Number.isInteger(copy[key])||copy[key]<0||copy[key]>15)return null;
    for(const [source,masks] of [['csp',CSP_MASKS],['resource',RESOURCE_MASKS]]) {
      if(!['OBSERVED','UNAVAILABLE'].includes(copy[source+'_observation']))return null;
      if(copy[source+'_observation']==='UNAVAILABLE'&&masks.some(key=>copy[key]!==0))return null;
    }
    const destination=copy.fetch_failure_destination,code=copy.fetch_failure_code;
    if(copy.resource_observation==='UNAVAILABLE') {
      if(destination!=='UNAVAILABLE'||code!=='UNAVAILABLE')return null;
    } else if(copy.resource_transport_failure_mask&4) {
      if(!DESTINATIONS.includes(destination)||!CODES.includes(code))return null;
    } else if(destination!=='NONE'||code!=='NONE')return null;
    const missing=copy.csp_observation==='UNAVAILABLE'&&copy.resource_observation==='UNAVAILABLE';
    if(missing?copy.environment_freshness!=='UNAVAILABLE':!['SAMPLED','CACHED'].includes(copy.environment_freshness))return null;
    return Object.freeze(copy);
  } catch {return null;}
}
export function unavailableFormEnvironmentEvidence() {
  return Object.freeze({csp_observation:'UNAVAILABLE',csp_enforced_mask:0,csp_report_only_mask:0,
    resource_observation:'UNAVAILABLE',resource_transport_failure_mask:0,resource_http_4xx_mask:0,
    resource_http_5xx_mask:0,fetch_failure_destination:'UNAVAILABLE',fetch_failure_code:'UNAVAILABLE',environment_freshness:'UNAVAILABLE'});
}
