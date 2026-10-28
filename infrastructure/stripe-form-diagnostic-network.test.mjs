import test from 'node:test';
import assert from 'node:assert/strict';
import {classifyFormFetchDestination, classifyFormFetchFailure} from './stripe-form-diagnostic-network.mjs';

const ownedOrigins=Object.freeze(['http://127.0.0.1:3000','http://127.0.0.1:8080']);
const sensitive='SENSITIVE_SENTINEL';

// Break caught: owned endpoints, Stripe hosts, or URL authority normalization misclassified.
test('destination classifies exact owned origins and HTTPS Stripe authorities',()=>{
  for(const [url,want] of [
    ['http://127.0.0.1:3000/checkout','OWN_APP'],
    ['http://127.0.0.1:8080/session','OWN_APP'],
    [`https://api.stripe.com/path?client_secret=${sensitive}#private`,'STRIPE_API'],
    ['https://API.STRIPE.COM:443/x','STRIPE_API'],
    ['https://js.stripe.com/v3','STRIPE_JS'],
    ['https://a.js.stripe.com/x','STRIPE_JS'],
    ['https://a.b.js.stripe.com:443/x','STRIPE_JS'],
  ])assert.equal(classifyFormFetchDestination(url,ownedOrigins),want);
});

// Break caught: substring hostname matching, wildcard loopback, or insecure/nondefault Stripe authority accepted.
test('destination keeps valid out-of-scope HTTP authorities in OTHER',()=>{
  for(const url of [
    'https://api.stripe.com.evil/x','https://eviljs.stripe.com/x',
    'https://js.stripe.com.evil/x','https://evilstripe.com/x','https://.js.stripe.com/x',
    'https://api.stripe.com:444/x','https://js.stripe.com:8443/x','https://a.js.stripe.com:444/x',
    'http://api.stripe.com/x','http://js.stripe.com/x','http://a.js.stripe.com/x',
    'https://api.stripe.com./x','https://js.stripe.com./x','https://example.test/x',
    'http://127.0.0.1:3001/x','http://localhost:3000/x','http://127.0.0.2:3000/x',
    'https://127.0.0.1:3000/x','https://example.test/api.stripe.com?host=js.stripe.com',
  ])assert.equal(classifyFormFetchDestination(url,ownedOrigins),'OTHER');
});

// Break caught: URL parser silently sanitizes forbidden controls or coerces untrusted inputs.
test('destination rejects malformed URLs, credentials, non-HTTP schemes, and every ASCII control-space',()=>{
  let coerced=0;
  const untrusted={toString(){coerced++;throw sensitive;},valueOf(){coerced++;throw sensitive;}};
  for(const value of [
    null,undefined,0,false,[],{},untrusted,new String('https://api.stripe.com'),
    '', '/relative','not-a-url','https://[invalid','https://api.stripe.com:99999/x',
    'ftp://api.stripe.com/x','data:text/plain,private','file:///private',
    'https://user:private@api.stripe.com/x','https://user@js.stripe.com/x',
    'https://:private@a.js.stripe.com/x',
    'http://user:private@127.0.0.1:3000/x',
    'https://api.stripe.com/'+ 'x'.repeat(8192),
  ])assert.equal(classifyFormFetchDestination(value,ownedOrigins),'UNKNOWN');
  for(let code=0;code<=32;code++) {
    const control=String.fromCharCode(code);
    assert.equal(classifyFormFetchDestination(`https://api.stripe.com/a${control}b`,ownedOrigins),'UNKNOWN');
    assert.equal(classifyFormFetchDestination(`${control}https://api.stripe.com`,ownedOrigins),'UNKNOWN');
  }
  assert.equal(classifyFormFetchDestination('https://api.stripe.com/a\u007fb',ownedOrigins),'UNKNOWN');
  assert.equal(coerced,0);
});

// Break caught: URL length boundary off by one, or encoded query/path used for classification.
test('destination accepts the 8192-character boundary without decoding private URL parts',()=>{
  const prefix='https://api.stripe.com/';
  assert.equal(classifyFormFetchDestination(prefix+'x'.repeat(8192-prefix.length),ownedOrigins),'STRIPE_API');
  assert.equal(classifyFormFetchDestination(prefix+'x'.repeat(8193-prefix.length),ownedOrigins),'UNKNOWN');
  assert.equal(classifyFormFetchDestination(`https://js.stripe.com/%20%7F?token=${sensitive}#${sensitive}`,ownedOrigins),'STRIPE_JS');
});

// Break caught: malformed or noncanonical ownership configuration turns arbitrary endpoints into OWN_APP.
test('destination requires two distinct frozen canonical high-port loopback origins',()=>{
  for(const origins of [
    null,undefined,{},'http://127.0.0.1:3000',[],
    ['http://127.0.0.1:3000','http://127.0.0.1:8080'],
    Object.freeze(['http://127.0.0.1:3000']),
    Object.freeze(['http://127.0.0.1:3000','http://127.0.0.1:3000']),
    Object.freeze([...ownedOrigins,'http://127.0.0.1:9000']),
    ...[
      'http://127.0.0.1:1023','http://127.0.0.1:65536','http://127.0.0.1',
      'http://127.0.0.1:03000','HTTP://127.0.0.1:3000','http://127.1:3000',
      'http://localhost:3000','http://127.0.0.2:3000','https://127.0.0.1:3000',
      'http://user:private@127.0.0.1:3000','http://127.0.0.1:3000/',
      'http://127.0.0.1:3000/path','http://127.0.0.1:3000?private',
      'http://127.0.0.1:3000#private','http://127.0.0.1:3000\n',null,42,{},
    ].map(origin=>Object.freeze([origin,'http://127.0.0.1:8080'])),
  ]) {
    assert.equal(classifyFormFetchDestination('https://api.stripe.com/x',origins),'UNKNOWN');
    assert.equal(classifyFormFetchDestination('http://127.0.0.1:3000/x',origins),'UNKNOWN');
    if(Array.isArray(origins)&&origins.length===2&&Object.isFrozen(origins)) {
      assert.equal(classifyFormFetchDestination('https://api.stripe.com/x',Object.freeze([origins[1],origins[0]])),'UNKNOWN');
    }
  }
  const limits=Object.freeze(['http://127.0.0.1:1024','http://127.0.0.1:65535']);
  assert.equal(classifyFormFetchDestination('http://127.0.0.1:1024/x',limits),'OWN_APP');
  assert.equal(classifyFormFetchDestination('http://127.0.0.1:65535/x',limits),'OWN_APP');
});

// Break caught: configuration getters/coercion methods execute, or reflective failures escape.
test('destination does not invoke owned-origin accessors or inspect reflective exceptions',()=>{
  let reads=0;
  const thrown=Object.defineProperties({},Object.fromEntries(['message','stack','toString','toJSON'].map(key=>[
    key,{get(){reads++;throw sensitive;}}
  ])));
  const accessor=['http://127.0.0.1:3000','http://127.0.0.1:8080'];
  Object.defineProperty(accessor,'0',{get(){reads++;throw thrown;}});
  Object.freeze(accessor);
  const coercible={toString(){reads++;throw thrown;}};
  const revoked=Proxy.revocable([],{});revoked.revoke();
  for(const origins of [accessor,Object.freeze([coercible,ownedOrigins[1]]),revoked.proxy,
    new Proxy(ownedOrigins,{ownKeys(){throw thrown;}}),
    new Proxy(ownedOrigins,{getOwnPropertyDescriptor(){throw thrown;}}),
  ])assert.equal(classifyFormFetchDestination('https://api.stripe.com/x',origins),'UNKNOWN');
  assert.equal(reads,0);
});

// Break caught: a Chromium code omitted or confused with another category; null-prototype data rejected.
test('failure classifies only the complete exact Chromium allowlist',()=>{
  for(const [errorText,want] of [
    ['net::ERR_ABORTED','ABORTED'],
    ['net::ERR_NAME_NOT_RESOLVED','NAME_NOT_RESOLVED'],
    ['net::ERR_CONNECTION_REFUSED','CONNECTION_REFUSED'],
    ['net::ERR_CONNECTION_RESET','CONNECTION_RESET'],
    ['net::ERR_TIMED_OUT','TIMED_OUT'],
    ['net::ERR_CONNECTION_TIMED_OUT','TIMED_OUT'],
    ['net::ERR_CERT_AUTHORITY_INVALID','CERTIFICATE'],
    ['net::ERR_CERT_COMMON_NAME_INVALID','CERTIFICATE'],
    ['net::ERR_CERT_DATE_INVALID','CERTIFICATE'],
    ['net::ERR_BLOCKED_BY_CLIENT','BLOCKED_BY_CLIENT'],
    ['net::ERR_FAILED','GENERIC_FAILED'],
  ]) {
    assert.equal(classifyFormFetchFailure({errorText}),want);
    assert.equal(classifyFormFetchFailure(Object.assign(Object.create(null),{errorText})),want);
    assert.equal(classifyFormFetchFailure(Object.freeze({errorText})),want);
    assert.equal(classifyFormFetchFailure({errorText:' '+errorText}),'UNKNOWN');
    assert.equal(classifyFormFetchFailure({errorText:errorText+' '+sensitive}),'UNKNOWN');
  }
});

// Break caught: partial/trimmed matching, arbitrary error strings, or implicit string coercion leaks a category.
test('failure rejects prefixes, suffixes, sensitive values, other dialects, and invalid scalar values',()=>{
  let coercions=0;
  for(const errorText of [
    '', 'ERR_ABORTED','NS_BINDING_ABORTED','Failed to fetch','net::ERR_CERT_REVOKED',
    'net::err_aborted','net::ERR_FAILED '+sensitive,sensitive+'net::ERR_FAILED',
    'net::ERR_FAILED\n',' net::ERR_FAILED','net::ERR_FAILED\0',
    'net::ERR_FAILED https://api.stripe.com?client_secret='+sensitive,
    'net::ERR_'+ 'x'.repeat(119), 'net::ERR_'+ 'x'.repeat(120),
    null,undefined,42,[],new String('net::ERR_ABORTED'),
    {toString(){coercions++;throw sensitive;}},
  ])assert.equal(classifyFormFetchFailure({errorText}),'UNKNOWN');
  assert.equal(coercions,0);
});

// Break caught: failure property access executes getters or accepts hidden/symbol/extra/prototype payloads.
test('failure rejects non-exact records without invoking payload accessors or methods',()=>{
  let reads=0;
  const hostile=()=>{reads++;throw sensitive;};
  const getter=Object.defineProperty({},'errorText',{enumerable:true,get:hostile});
  const hidden=Object.defineProperty({},'errorText',{value:'net::ERR_FAILED'});
  const setter=Object.defineProperty({},'errorText',{enumerable:true,set:hostile});
  const inherited=Object.create({errorText:'net::ERR_FAILED'});
  const customPrototype=Object.assign(Object.create({}),{errorText:'net::ERR_FAILED'});
  const hiddenExtra=Object.defineProperty({errorText:'net::ERR_FAILED'},'private',{value:sensitive});
  const symbolExtra={errorText:'net::ERR_FAILED',[Symbol('private')]:sensitive};
  const extraGetter=Object.defineProperty({errorText:'net::ERR_FAILED'},'private',{enumerable:true,get:hostile});
  const error=new Error(sensitive);error.errorText='net::ERR_FAILED';
  const methods={errorText:'net::ERR_FAILED',toString:hostile,toJSON:hostile};
  const methodGetters=Object.defineProperties({errorText:'net::ERR_FAILED'},Object.fromEntries(
    ['message','stack','toString','toJSON'].map(key=>[key,{get:hostile}])));
  for(const value of [null,undefined,1,'net::ERR_FAILED',[],()=>{},new Date(0),{},
    getter,hidden,setter,inherited,customPrototype,hiddenExtra,symbolExtra,extraGetter,error,
    methods,methodGetters,{errorText:'net::ERR_FAILED',private:sensitive},
  ])assert.equal(classifyFormFetchFailure(value),'UNKNOWN');
  assert.equal(reads,0);
});

// Break caught: reflection errors rethrow or their payload is interrogated/logged.
test('failure turns all reflection failures into UNKNOWN without inspecting thrown data',()=>{
  let reads=0;
  const thrown=Object.defineProperties({},Object.fromEntries(['message','stack','toString','toJSON'].map(key=>[
    key,{get(){reads++;throw sensitive;}}
  ])));
  const revoked=Proxy.revocable({},{});revoked.revoke();
  for(const value of [revoked.proxy,
    new Proxy({},{getPrototypeOf(){throw thrown;}}),
    new Proxy({},{ownKeys(){throw thrown;}}),
    new Proxy({errorText:'net::ERR_FAILED'},{getOwnPropertyDescriptor(){throw thrown;}}),
  ])assert.equal(classifyFormFetchFailure(value),'UNKNOWN');
  assert.equal(reads,0);
});
