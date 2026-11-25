import {createHash} from 'node:crypto';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
import {parseSubscriptionPortal,validSubscriptionReturnUrl} from '../../../../frontend/sdk/dist/subscriptions/wire.js';

type Limits = {deadline:number; signal?:AbortSignal};
type PortalAuthority = Readonly<{
  customerId:string; siteId:string; subscriptionId:string; bearer:string;
  revision:number; frame:object; document:object; returnUrl:string;
}>;
type PortalPolicy = Readonly<{portalReturnUrl:string; portalPolicyVersion:number}>;
type Scope = Readonly<{backendOrigin:string; demoOrigin:string; authority:PortalAuthority; policy:PortalPolicy}>;
interface BrowserRequest {
  url():string;
  method():string;
  frame():object;
  redirectedFrom():object|null;
  serviceWorker():object|null;
  isNavigationRequest():boolean;
  postDataBuffer():Buffer|null;
  existingResponse():object|null;
}
interface PortalRequest extends BrowserRequest {
  allHeaders():Promise<Record<string,string>>;
  existingResponse():PortalResponse|null;
}
interface PortalResponse {
  request():PortalRequest;
  url():string;
  status():number;
  fromServiceWorker():boolean;
  headerValue(name:string):Promise<string|null>;
}
interface HostedRoute<Request extends BrowserRequest> {
  request():Request;
  continue():Promise<void>;
}
type HostedTicket = Readonly<{expiresAt:string; digest:string}>;
type ReadOriginalBody = (request:PortalRequest,response:PortalResponse,limits:Readonly<Limits>)=>Promise<{measured:number;bytes:Uint8Array}>;

const maximumBody = 32768;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const bearer = /^Bearer [A-Za-z0-9_-]{43}$/;
const idempotencyKey = /^[A-Za-z0-9:_-]{1,255}$/;
const json = /^application\/json(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?$/i;
const hostedUrl = /^https:\/\/billing\.stripe\.com\/p\/session\?secret=test_[A-Za-z0-9_-]+$/;
const loopback = /^http:\/\/127\.0\.0\.1:[1-9][0-9]{3,4}$/;
const sha256 = (value:string) => createHash('sha256').update(value).digest('hex');
const fail = ():never => { throw new Error('billing_BROWSER_PORTAL_RECEIPT'); };
function requireFact(fact:unknown):asserts fact { if (!fact) fail(); }

/** Validate the exact captured response body. The capability leaves this function only as a digest. */
export function validatePortal201Body(bytes:Uint8Array):HostedTicket {
  try {
    requireFact(bytes.byteLength>0 && bytes.byteLength<=maximumBody);
    const text = new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);
    const portal = parseSubscriptionPortal(JSON.parse(text));
    // JSON.parse collapses duplicate names before the SDK can check the property count.
    requireFact(!portal.simulated && [...text.matchAll(/"(?:[^"\\]|\\.)*"\s*:/gs)].length===3);
    return Object.freeze({expiresAt:portal.expiresAt,digest:sha256(portal.navigationUrl)});
  } catch { return fail(); }
}

/** Guard one original Portal request and one hosted route. The owner supplies live document and response interception. */
export function createPortal201Guard(
  scope:Scope,readAuthority:()=>PortalAuthority|null|Promise<PortalAuthority|null>,limits:Limits,
  readOriginalBody:ReadOriginalBody,
) {
  const expected = Object.freeze({...scope.authority});
  const policy = Object.freeze({...scope.policy});
  const demoOrigin = scope.demoOrigin;
  const backendOrigin = scope.backendOrigin;
  const endpoint = backendOrigin+'/api/v1/customer/subscriptions/'+expected.subscriptionId+'/portal-sessions';
  const requestBody = Buffer.from(JSON.stringify({return_url:expected.returnUrl}));
  const ticket = Promise.withResolvers<HostedTicket|null>();
  let claimStarted = false;
  let responseStarted = false;
  let navigationStarted = false;
  let closed = false;
  let claimed:PortalRequest|undefined;
  let paired:PortalResponse|undefined;

  async function requireAuthority(active:Limits) {
    const current = await readAuthority();
    billingCheck(active);
    requireFact(!closed && current && current.customerId===expected.customerId && current.siteId===expected.siteId &&
      current.subscriptionId===expected.subscriptionId && current.bearer===expected.bearer &&
      current.revision===expected.revision && current.frame===expected.frame && current.document===expected.document &&
      current.returnUrl===expected.returnUrl);
  }

  function close():never {
    closed = true;
    claimed = undefined;
    paired = undefined;
    ticket.resolve(null);
    return fail();
  }

  function requireRequest(request:PortalRequest) {
    requireFact(request.url()===endpoint && request.method()==='POST' && request.frame()===expected.frame &&
      request.redirectedFrom()===null && request.serviceWorker()===null && request.isNavigationRequest()===false &&
      request.postDataBuffer()?.equals(requestBody));
  }

  function requireOriginalResponse(request:PortalRequest,response:PortalResponse) {
    requireRequest(request);
    requireFact(claimed===request && response.request()===request && request.existingResponse()===response && response.url()===endpoint &&
      response.status()===201 && response.fromServiceWorker()===false);
  }

  async function claimRequest(request:PortalRequest):Promise<string> {
    try {
      requireFact(!closed && !claimStarted);
      claimStarted = true;
      requireFact(bearer.test(expected.bearer) && uuid.test(expected.siteId) && uuid.test(expected.customerId) &&
        uuid.test(expected.subscriptionId) && Number.isSafeInteger(expected.revision) && expected.revision>=0 && expected.document &&
        policy.portalPolicyVersion===1 && policy.portalReturnUrl===expected.returnUrl &&
        loopback.test(demoOrigin) && loopback.test(backendOrigin) && demoOrigin!==backendOrigin &&
        validSubscriptionReturnUrl(expected.returnUrl) && new URL(expected.returnUrl).origin===demoOrigin &&
        validSubscriptionReturnUrl(backendOrigin+'/') && new URL(backendOrigin).origin===backendOrigin);
      return await billingBounded(limits,async active => {
        await requireAuthority(active);
        requireRequest(request);
        requireFact(request.existingResponse()===null);
        const headers = await request.allHeaders();
        const key = headers['idempotency-key'];
        requireFact(headers.origin===demoOrigin && headers.authorization===expected.bearer &&
          headers['content-type']==='application/json' && !Object.hasOwn(headers,'cookie') &&
          !Object.hasOwn(headers,'proxy-authorization') && typeof key==='string' && idempotencyKey.test(key));
        await requireAuthority(active);
        requireRequest(request);
        requireFact(request.existingResponse()===null);
        claimed = request;
        return sha256(key);
      });
    } catch { return close(); }
  }

  async function observe201(response:PortalResponse) {
    try {
      const request = claimed;
      requireFact(!closed && !responseStarted && request);
      responseStarted = true;
      const observed = await billingBounded(limits,async active => {
        const requireLiveResponse = async () => {
          billingCheck(active);
          await requireAuthority(active);
          requireOriginalResponse(request,response);
        };
        await requireLiveResponse();
        const [type,encoding,length,cache] = await Promise.all([
          response.headerValue('content-type'),response.headerValue('content-encoding'),
          response.headerValue('content-length'),response.headerValue('cache-control'),
        ]);
        await requireLiveResponse();
        requireFact(type && json.test(type.trim()) && (encoding===null || encoding.trim().toLowerCase()==='identity') &&
          cache?.trim().toLowerCase()==='no-store' &&
          length!==null && /^[1-9][0-9]{0,4}$/.test(length) && Number(length)<=maximumBody);
        const {measured,bytes} = await readOriginalBody(request,response,Object.freeze({...active}));
        await requireLiveResponse();
        requireFact(Number.isSafeInteger(measured) && measured===Number(length) && bytes.byteLength===measured);
        const receipt = validatePortal201Body(bytes);
        await requireLiveResponse();
        return receipt;
      });
      billingCheck(limits);
      requireOriginalResponse(request,response);
      paired = response;
      ticket.resolve(observed);
      return Object.freeze({expiresAt:observed.expiresAt});
    } catch { return close(); }
  }

  async function continueHostedNavigation<Request extends BrowserRequest>(
    route:HostedRoute<Request>,armResponse:(request:Request)=>true|Promise<true>,
  ):Promise<void> {
    try {
      const original = claimed;
      requireFact(!closed && original && !navigationStarted);
      navigationStarted = true;
      const request = route.request();
      const requireHostedRequest = () => {
        const url=request.url();
        requireFact(route.request()===request && !Object.is(request,original) && request.frame()===expected.frame &&
          request.method()==='GET' && request.isNavigationRequest()===true && request.postDataBuffer()===null &&
          request.redirectedFrom()===null && request.serviceWorker()===null && request.existingResponse()===null &&
          url.length<=2048 && hostedUrl.test(url));
        return sha256(url);
      };
      const requestedDigest=requireHostedRequest();
      await billingBounded(limits,async active => {
        const permit=await ticket.promise;
        await requireAuthority(active);
        const response=paired;
        requireFact(permit && response && permit.digest===requestedDigest && permit.digest===requireHostedRequest() && Date.parse(permit.expiresAt)>Date.now());
        requireOriginalResponse(original,response);
        requireFact(await armResponse(request)===true);
        await requireAuthority(active);
        requireFact(permit.digest===requireHostedRequest() && Date.parse(permit.expiresAt)>Date.now());
        requireOriginalResponse(original,response);
        await route.continue();
        billingCheck(active);
        requireFact(!closed);
        claimed=undefined;
        paired=undefined;
      });
    } catch { return close(); }
  }
  return Object.freeze({claimRequest,observe201,continueHostedNavigation});
}
