const ASCII_CONTROL_OR_SPACE=/[\u0000-\u0020\u007f]/;

function isUrlString(value) {
  return typeof value==='string'&&value.length>0&&value.length<=8192&&!ASCII_CONTROL_OR_SPACE.test(value);
}

/** Read only frozen array data descriptors, never caller accessors or methods. */
function readOwnedOrigins(value) {
  if(!Array.isArray(value)||!Object.isFrozen(value))return null;
  const descriptors=Object.getOwnPropertyDescriptors(value);
  if(Reflect.ownKeys(descriptors).length!==3||descriptors.length?.value!==2)return null;
  const origins=[];
  for(const key of ['0','1']) {
    const descriptor=descriptors[key];
    if(!descriptor||!Object.hasOwn(descriptor,'value')||!descriptor.enumerable)return null;
    const origin=descriptor.value;
    if(!isUrlString(origin))return null;
    const parsed=new URL(origin),port=Number(parsed.port);
    if(parsed.protocol!=='http:'||parsed.hostname!=='127.0.0.1'||
      parsed.username!==''||parsed.password!==''||parsed.origin!==origin||
      !Number.isInteger(port)||port<1024||port>65535)return null;
    origins.push(origin);
  }
  return origins[0]===origins[1]?null:origins;
}

/** Classify transient metadata; return only fixed literals and retain no input. */
export function classifyFormFetchDestination(rawUrl,ownedOrigins) {
  try {
    if(!isUrlString(rawUrl))return 'UNKNOWN';
    const origins=readOwnedOrigins(ownedOrigins);
    if(!origins)return 'UNKNOWN';
    const parsed=new URL(rawUrl);
    if((parsed.protocol!=='http:'&&parsed.protocol!=='https:')||
      parsed.username!==''||parsed.password!=='')return 'UNKNOWN';
    if(parsed.origin===origins[0]||parsed.origin===origins[1])return 'OWN_APP';
    if(parsed.protocol==='https:'&&parsed.port==='') {
      if(parsed.hostname==='api.stripe.com')return 'STRIPE_API';
      if(parsed.hostname==='js.stripe.com'||
        (parsed.hostname.length>'.js.stripe.com'.length&&parsed.hostname.endsWith('.js.stripe.com')))return 'STRIPE_JS';
    }
    return 'OTHER';
  } catch {return 'UNKNOWN';}
}

/** Inspect the exact record shape before considering its one allowed data value. */
export function classifyFormFetchFailure(rawFailure) {
  try {
    if(rawFailure===null||typeof rawFailure!=='object')return 'UNKNOWN';
    const prototype=Object.getPrototypeOf(rawFailure);
    if(prototype!==Object.prototype&&prototype!==null)return 'UNKNOWN';
    const descriptors=Object.getOwnPropertyDescriptors(rawFailure),keys=Reflect.ownKeys(descriptors);
    if(keys.length!==1||keys[0]!=='errorText')return 'UNKNOWN';
    const descriptor=descriptors.errorText;
    if(!descriptor.enumerable||!Object.hasOwn(descriptor,'value'))return 'UNKNOWN';
    const errorText=descriptor.value;
    if(typeof errorText!=='string'||errorText.length>128)return 'UNKNOWN';
    switch(errorText) {
      case 'net::ERR_ABORTED':return 'ABORTED';
      case 'net::ERR_NAME_NOT_RESOLVED':return 'NAME_NOT_RESOLVED';
      case 'net::ERR_CONNECTION_REFUSED':return 'CONNECTION_REFUSED';
      case 'net::ERR_CONNECTION_RESET':return 'CONNECTION_RESET';
      case 'net::ERR_TIMED_OUT':
      case 'net::ERR_CONNECTION_TIMED_OUT':return 'TIMED_OUT';
      case 'net::ERR_CERT_AUTHORITY_INVALID':
      case 'net::ERR_CERT_COMMON_NAME_INVALID':
      case 'net::ERR_CERT_DATE_INVALID':return 'CERTIFICATE';
      case 'net::ERR_BLOCKED_BY_CLIENT':return 'BLOCKED_BY_CLIENT';
      case 'net::ERR_FAILED':return 'GENERIC_FAILED';
      default:return 'UNKNOWN';
    }
  } catch {return 'UNKNOWN';}
}
