import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {constants} from 'node:fs';
import {lstat,realpath,open,mkdir,rename} from 'node:fs/promises';
import {dirname,resolve,parse as parsePath} from 'node:path';
import {fileURLToPath} from 'node:url';
import {timingSafeEqual} from 'node:crypto';
import {validateManualRefundRemainderOptions} from './stripe-manual-refund-remainder-options.mjs';
import {parseRefundRetainedJSON,decodeOriginalRefundTarget,decodeOriginalRefundEnv} from './stripe-manual-refund-retained.mjs';
import {createFormDiagnosticSettlement} from './stripe-form-diagnostic-settlement.mjs';
const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const ORIGINAL='031b5ae7-6603-4339-b1b3-77231291ff34',SOURCE='f0620e40004162d2b3eededa60512f50a492f4cc',PRIOR='34941d87-12d3-4c74-8c5a-b62b7c914390';
const RESTORE=['postgres','mailpit','callback-receiver','demo-site','backend'],KEY='s5a-refund:'+PRIOR+':remainder';
const fail=()=>{throw null;},fixed=()=>new Error('stripe_manual_refund_remainder_state_PENDING');
const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
const provider=(v,prefix)=>typeof v==='string'&&new RegExp('^'+prefix+'[A-Za-z0-9_]{1,249}$').test(v);
function exact(v,keys){if(!v||typeof v!=='object'||Array.isArray(v)||![Object.prototype,null].includes(Object.getPrototypeOf(v)))fail();const d=Object.getOwnPropertyDescriptors(v);if(Reflect.ownKeys(d).length!==keys.length||keys.some(k=>!d[k]?.enumerable||!Object.hasOwn(d[k],'value')))fail();return Object.fromEntries(keys.map(k=>[k,d[k].value]));}
// Normalize only verified own data: comparisons must never evaluate caller accessors.
function copy(v,depth=0){if(depth>32)fail();if(v===null||['string','boolean','number'].includes(typeof v))return v;if(!v||typeof v!=='object')fail();const d=Object.getOwnPropertyDescriptors(v);if(Array.isArray(v)){if(Reflect.ownKeys(d).length!==v.length+1)fail();return Array.from({length:v.length},(_,i)=>{const p=d[i];if(!p?.enumerable||!Object.hasOwn(p,'value'))fail();return copy(p.value,depth+1);});}if(![Object.prototype,null].includes(Object.getPrototypeOf(v)))fail();return Object.fromEntries(Reflect.ownKeys(d).map(k=>{if(typeof k!=='string'||!d[k].enumerable||!Object.hasOwn(d[k],'value'))fail();return [k,copy(d[k].value,depth+1)];}));}
function canonical(v){return Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;}
const same=(a,b)=>JSON.stringify(canonical(a))===JSON.stringify(canonical(b));
function frozen(v){if(v&&typeof v==='object'){for(const child of Object.values(v))frozen(child);Object.freeze(v);}return v;}
function priorState(raw,target,oldLock,now){
 const p=exact(raw,['version','mode','runId','expectedSource','originalRunId','originalSource','deadline','siteId','paymentId','pending','restore','rotation','configure','legs']),l=exact(oldLock,['version','runId','expectedSource']);
 if(p.version!==1||p.mode!=='MANUAL_REFUND'||p.runId!==PRIOR||typeof p.expectedSource!=='string'||!/^[0-9a-f]{40}$/.test(p.expectedSource)||p.originalRunId!==ORIGINAL||p.originalSource!==SOURCE||!Number.isSafeInteger(p.deadline)||p.deadline<=0||p.deadline+120000>=now||p.siteId!==target.site.id||p.paymentId!==target.paymentId||typeof p.pending!=='boolean'||!same(l,{version:1,runId:PRIOR,expectedSource:p.expectedSource}))fail();
 if(!same(p.restore,RESTORE.map(service=>({service,phase:'ACKNOWLEDGED'})))||!same(p.rotation,{phase:'ACKNOWLEDGED',version:2})||!same(p.configure,{phase:'ACKNOWLEDGED',version:2})||!Array.isArray(p.legs)||p.legs.length!==2)fail();
 const partial=exact(p.legs[0],['amountMinor','key','phase','refundId','providerReference']),remainder=exact(p.legs[1],['amountMinor','key','phase','refundId','providerReference']);
 if(!uuid(partial.refundId)||!same(partial,{amountMinor:700,key:'s5a-refund:'+PRIOR+':partial',phase:'ACKNOWLEDGED',refundId:partial.refundId,providerReference:null})||!same(remainder,{amountMinor:1300,key:KEY,phase:'READY',refundId:null,providerReference:null}))fail();return p;
}
/** Construction has no IO; admission is a separate one-shot capability. */
export function createManualRefundRemainderState(input,dependencies={}){
 const options=validateManualRefundRemainderOptions(input);
 let io,root,original,directory,lockPath,statePath,settlement,targetValue,admin=null,verifier=null,lock=null,busy=false,pending=false,closed=false,cleanupContext=null,sequence=0,state,authority=null,admitted=false,ready=false,sideEffects=false;
 const identities=new Map(),directories=new Map(),retained=new Map(),owned=new Map(),work=new Set();
 const identity=s=>({ino:s.ino,dev:s.dev}),stable=s=>({ino:s.ino,dev:s.dev,size:s.size,mtimeMs:s.mtimeMs,ctimeMs:s.ctimeMs});
 function validateLimits(value, cleanup = false) {
     const d = Object.getOwnPropertyDescriptors(value), v = exact(value, Object.hasOwn(d, 'signal') ? ['deadline', 'signal'] : ['deadline']);
     if (!Number.isSafeInteger(v.deadline) || v.deadline <= io.now() || v.signal !== undefined && !(v.signal instanceof AbortSignal) || v.signal?.aborted || authority && (cleanup ? v.deadline > authority.deadline + 120000 : v.deadline !== authority.deadline))
         fail();
     if (!cleanup && authority?.signal) {
         if (authority.signal.aborted)
             fail();
         // Caller cancellation can narrow the admission authority, never replace it.
         if (v.signal !== authority.signal)
             v.signal = v.signal ? AbortSignal.any([authority.signal, v.signal]) : authority.signal;
     }
     return Object.freeze(v);
 }
 function check(context) {
     if (closed && context !== cleanupContext || context !== cleanupContext && authority?.signal?.aborted || context.signal?.aborted || io.now() >= context.deadline)
         fail();
 }
 function track(fn) {
     const p = Promise.resolve().then(fn);
     work.add(p);
     p.then(() => work.delete(p), () => work.delete(p));
     return p;
 }
 async function call(context, fn, dispose) {
     check(context);
     return settlement.call(context, () => {
         check(context);
         return track(fn);
     }, dispose ? value => track(() => dispose(value)) : undefined);
 }
 async function handle(path,flags,context,mode){return call(context,()=>io.open(path,flags,mode),file=>file.close());}
 async function release(file){await settlement.release(file);}
 function fileInfo(s){if(!s.isFile()||s.isSymbolicLink()||s.uid!==process.getuid()||(s.mode&0o7777)!==0o600||s.nlink!==1||!Number.isSafeInteger(s.size)||s.size>65536)fail();}
 async function directoryProof(path, context) {
     const paths = [];
     for (let current = path;; current = dirname(current)) {
         paths.unshift(current);
         if (current === parsePath(current).root)
             break;
     }
     for (const current of paths) {
         const info = await call(context, () => io.lstat(current));
         if (!info.isDirectory() || info.isSymbolicLink() || ![0, process.getuid()].includes(info.uid) || await call(context, () => io.realpath(current)) !== current)
             fail();
         if (current === root || current.startsWith(root + '/')) {
             if (info.uid !== process.getuid() || current !== root && (info.mode & 0o7777) !== 0o700)
                 fail();
             const prior = directories.get(current);
             if (prior && !same(identity(info), prior))
                 fail();
             directories.set(current, identity(info));
         }
     }
 }
 async function fileProof(path, file, context, expected = identities.get(path)) {
     await directoryProof(dirname(path), context);
     const held = await call(context, () => file.stat()), named = await call(context, () => io.lstat(path));
     fileInfo(held);
     fileInfo(named);
     if (!same(stable(held), stable(named)) || expected && !same(identity(held), expected) || await call(context, () => io.realpath(path)) !== path)
         fail();
     return held;
 }
 async function readRetained(path, context) {
     const before = await call(context, () => io.lstat(path));
     fileInfo(before);
     const file = await handle(path, constants.O_RDONLY | constants.O_NOFOLLOW, context);
     try {
         const held = await fileProof(path, file, context, identity(before)), bytes = Buffer.alloc(held.size + 1), read = await call(context, () => file.read(bytes, 0, bytes.length, 0));
         if (read.bytesRead !== held.size)
             fail();
         const after = await fileProof(path, file, context, identity(held));
         if (!same(stable(held), stable(after)))
             fail();
         retained.set(path, stable(after));
         return new TextDecoder('utf-8', {
             fatal: true, ignoreBOM: true
         }).decode(bytes.subarray(0, read.bytesRead));
     }
     finally {
         await release(file);
     }
 }
 async function retainedProof(context) {
     for (const [path, prior] of retained) {
         await directoryProof(dirname(path), context);
         const current = await call(context, () => io.lstat(path));
         fileInfo(current);
         if (!same(stable(current), prior) || await call(context, () => io.realpath(path)) !== path)
             fail();
     }
 }
 async function absent(path,context){await call(context,async()=>{try{await io.lstat(path);}catch(error){if(error?.code==='ENOENT')return;throw error;}fail();});}
 async function syncDirectory(path, context) {
     await directoryProof(path, context);
     const file = await handle(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_DIRECTORY, context);
     try {
         const held = await call(context, () => file.stat());
         if (!held.isDirectory() || !same(identity(held), directories.get(path)))
             fail();
         await call(context, () => file.sync());
         await directoryProof(path, context);
     }
     finally {
         await release(file);
     }
 }
 async function exclusive(path, text, context, hold = false) {
     await directoryProof(dirname(path), context);
     const file = await handle(path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, context, 0o600);
     let held = false;
     try {
         const before = await fileProof(path, file, context);
         await call(context, () => file.writeFile(text));
         await call(context, () => file.sync());
         const after = await fileProof(path, file, context, identity(before));
         identities.set(path, identity(after));
         owned.set(path, stable(after));
         await syncDirectory(dirname(path), context);
         if (hold) {
             held = true;
             return file;
         }
     }
     finally {
         if (!held)
             await release(file);
     }
 }
 async function ownedState(context) {
     for (const path of [lockPath, statePath]) {
         if (!identities.has(path)) {
             if (path === statePath)
                 await absent(path, context);
             continue;
         }
         const file = path === lockPath ? lock : await handle(path, constants.O_RDONLY | constants.O_NOFOLLOW, context);
         try {
             const info = await fileProof(path, file, context);
             if (!same(stable(info), owned.get(path)))
                 fail();
         }
         finally {
             if (path !== lockPath)
                 await release(file);
         }
     }
 }
 async function save(context) {
     await retainedProof(context);
     await ownedState(context);
     const temporary = directory + '/.manual-refund-remainder-' + (++sequence) + '.tmp', text = JSON.stringify(state) + '\n';
     if (Buffer.byteLength(text) > 65536)
         fail();
     await exclusive(temporary, text, context);
     await ownedState(context);
     const file = await handle(temporary, constants.O_RDONLY | constants.O_NOFOLLOW, context);
     try {
         const info = await fileProof(temporary, file, context);
         if (!same(stable(info), owned.get(temporary)))
             fail();
     }
     finally {
         await release(file);
     }
     await call(context, () => io.rename(temporary, statePath));
     identities.set(statePath, identities.get(temporary));
     identities.delete(temporary);
     const expected = owned.get(temporary);
     owned.delete(temporary);
     owned.set(statePath, expected);
     const committed = await handle(statePath, constants.O_RDONLY | constants.O_NOFOLLOW, context);
     try {
         const info = await fileProof(statePath, committed, context);
         if (!same({
             ...stable(info), ctimeMs: expected.ctimeMs
         }, expected))
             fail();
         owned.set(statePath, stable(info));
     }
     finally {
         await release(committed);
     }
     await syncDirectory(directory, context);
 }
 async function git(args, context) {
     const settings = {
         cwd: root, env: {
             PATH: '/usr/bin:/bin:/usr/sbin:/sbin', LANG: 'C', LC_ALL: 'C', GIT_OPTIONAL_LOCKS: '0', GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_SYSTEM: '/dev/null'
         }, shell: false, timeout: Math.min(10000, context.deadline - io.now()), maxBuffer: 65536, signal: context.signal
     }, output = await call(context, () => io.execute('/usr/bin/git', args, settings));
     if (typeof output?.stdout !== 'string' || typeof output.stderr !== 'string' || Buffer.byteLength(output.stdout) + Buffer.byteLength(output.stderr) > 65536)
         fail();
     return output.stdout;
 }
 async function admit(value) {
     if (admitted || closed || busy) {
         pending = true;
         return 'PENDING';
     }
     admitted = true;
     busy = true;
     try {
         if (!options)
             fail();
         io = {
             lstat: dependencies.lstat ?? lstat, realpath: dependencies.realpath ?? realpath, open: dependencies.open ?? open, mkdir: dependencies.mkdir ?? mkdir, rename: dependencies.rename ?? rename, execute: dependencies.execute ?? promisify(execFile), now: dependencies.now ?? Date.now, setTimeout: dependencies.setTimeout ?? setTimeout, clearTimeout: dependencies.clearTimeout ?? clearTimeout
         };
         root = dependencies.root ?? ROOT;
         if (typeof root !== 'string' || resolve(root) !== root)
             fail();
         authority = validateLimits(value);
         if (authority.deadline > io.now() + 1800000)
             fail();
         settlement = createFormDiagnosticSettlement(io);
         settlement.bind(authority.deadline);
         original = root + '/test-results/stripe-sandbox/' + ORIGINAL;
         directory = root + '/test-results/stripe-sandbox/' + options.runId;
         lockPath = original + '/manual-refund-remainder.lock';
         statePath = directory + '/manual-refund-remainder.json';
         if ((await git(['rev-parse', 'HEAD'], authority)).trim() !== options.expectedSource || await git(['-c', 'core.fsmonitor=false', 'status', '--porcelain=v1', '--untracked-files=no'], authority) !== '')
             fail();
         await git(['merge-base', '--is-ancestor', SOURCE, options.expectedSource], authority);
         const socketPath = new URL(options.daemon).pathname;
         await directoryProof(dirname(socketPath), authority);
         const socket = await call(authority, () => io.lstat(socketPath));
         if (!socket.isSocket() || socket.isSymbolicLink() || socket.uid !== process.getuid() || await call(authority, () => io.realpath(socketPath)) !== socketPath)
             fail();
         await directoryProof(original, authority);
         await absent(lockPath, authority);
         await absent(directory, authority);
         const originalTarget = decodeOriginalRefundTarget({
             runtime: parseRefundRetainedJSON(await readRetained(original + '/runtime.json', authority)), provision: parseRefundRetainedJSON(await readRetained(original + '/form-provisioning.json', authority))
         }, {
             root, originalDirectory: original, daemon: options.daemon
         });
         const oldLock = parseRefundRetainedJSON(await readRetained(original + '/manual-refund.lock', authority)), prior = priorState(parseRefundRetainedJSON(await readRetained(root + '/test-results/stripe-sandbox/' + PRIOR + '/manual-refund.json', authority)), originalTarget, oldLock, io.now());
         await git(['merge-base', '--is-ancestor', prior.expectedSource, options.expectedSource], authority);
         const app = decodeOriginalRefundEnv(await readRetained(original + '/app.env', authority), false, originalTarget.ports), final = decodeOriginalRefundEnv(await readRetained(original + '/final.env', authority), true, originalTarget.ports);
         for (const key of Object.keys(app))
             if (app[key] !== final[key])
                 fail();
         admin = Object.freeze({
             username: app.PAYMENT_ADMIN_BOOTSTRAP_USERNAME, password: app.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD
         });
         verifier = final.PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET;
         targetValue = frozen({
             original: originalTarget, priorRunId: PRIOR, priorSource: prior.expectedSource, priorRefundId: prior.legs[0].refundId, remainderKey: KEY, callbackVersionBefore: 2, callbackVersionAfter: 3
         });
         await retainedProof(authority);
         // Any ambiguous exclusive open is side-effecting; only EEXIST proves no acquisition.
         sideEffects = true;
         try {
             lock = await exclusive(lockPath, JSON.stringify({
                 version: 1, mode: 'MANUAL_REFUND_REMAINDER', runId: options.runId, expectedSource: options.expectedSource, originalRunId: ORIGINAL, originalSource: SOURCE, priorRunId: PRIOR, priorSource: prior.expectedSource
             }) + '\n', authority, true);
         }
         catch (error) {
             if (error?.code === 'EEXIST')
                 sideEffects = false;
             throw error;
         }
         await call(authority, () => io.mkdir(directory, {
             mode: 0o700
         }));
         await directoryProof(directory, authority);
         await syncDirectory(dirname(directory), authority);
         state = {
             version: 1, mode: 'MANUAL_REFUND_REMAINDER', runId: options.runId, expectedSource: options.expectedSource, originalRunId: ORIGINAL, originalSource: SOURCE, priorRunId: PRIOR, priorSource: prior.expectedSource, deadline: authority.deadline, siteId: originalTarget.site.id, paymentId: originalTarget.paymentId, pending: false, stopped: null, restore: RESTORE.map(service => ({
                 service, phase: 'READY', startedAt: null
             })), baseline: null, rotation: {
                 phase: 'READY', version: null
             }, configure: {
                 phase: 'READY', version: null
             }, remainder: {
                 amountMinor: 1300, key: KEY, phase: 'READY', refundId: null, providerReference: null
             }, final: null
         };
         await save(authority);
         check(authority);
         if (pending)
             fail();
         ready = true;
         return 'READY';
     }
     catch {
         pending = true;
         admin = null;
         verifier = null;
         targetValue = null;
         return sideEffects ? 'PENDING' : 'DENIED';
     }
     finally {
         busy = false;
     }
 }
 async function operation(value, body) {
     if (busy) {
         pending = true;
         throw fixed();
     }
     busy = true;
     try {
         const active = validateLimits(value);
         if (!ready || closed || pending)
             fail();
         await retainedProof(active);
         await ownedState(active);
         const result = await settlement.wait(active, () => body(active));
         check(active);
         if (pending)
             fail();
         return result;
     }
     catch {
         pending = true;
         if (state)
             state.pending = true;
         throw fixed();
     }
     finally {
         busy = false;
     }
 }
 const transition=(value,body)=>operation(value,async active=>{body();await save(active);return true;});
 const phase=(item,want)=>{if(item.phase!==want)fail();};
 function proof(value,partial){const p=exact(value,['refundId','providerReference','providerEventId','cashEventId','amountMinor','totalMinor','providerVerified','webhookVerified','journalVerified',partial?'callbackDeliveryVerified':'callbackSignatureVerified']);if(p.refundId!==(partial?targetValue.priorRefundId:state.remainder.refundId)||!provider(p.providerReference,'re_')||!provider(p.providerEventId,'evt_')||!uuid(p.cashEventId)||p.amountMinor!==(partial?700:1300)||p.totalMinor!==(partial?700:2000)||['providerVerified','webhookVerified','journalVerified',partial?'callbackDeliveryVerified':'callbackSignatureVerified'].some(k=>p[k]!==true)||!partial&&(p.providerReference===state.baseline.providerReference||p.providerEventId===state.baseline.providerEventId||p.cashEventId===state.baseline.cashEventId))fail();return p;}
 async function close(value) {
     const overlapping = busy, repeated = closed;
     closed = true;
     ready = false;
     admin = null;
     verifier = null;
     targetValue = null;
     let failed = overlapping || repeated;
     try {
         if (!settlement) {
             const d = Object.getOwnPropertyDescriptors(value), v = exact(value, Object.hasOwn(d, 'signal') ? ['deadline', 'signal'] : ['deadline']);
             if (!Number.isSafeInteger(v.deadline) || v.deadline <= Date.now() || v.signal !== undefined && !(v.signal instanceof AbortSignal) || v.signal?.aborted)
                 fail();
         }
         else {
             const active = validateLimits(value, true);
             cleanupContext = active;
             if (!overlapping && lock) {
                 await retainedProof(active);
                 await ownedState(active);
             }
         }
     }
     catch {
         failed = true;
     }
     finally {
         if (settlement && authority && io.now() < authority.deadline + 120000) {
             const disposal = {
                 deadline: authority.deadline + 120000
             };
             try {
                 const releasing = lock ? settlement.release(lock) : Promise.resolve();
                 lock = null;
                 releasing.catch(() => {
                 });
                 settlement.close(disposal);
                 await settlement.wait(disposal, async () => {
                     await releasing;
                     while (work.size) {
                         await Promise.allSettled([...work]);
                         await Promise.resolve();
                     }
                 });
                 if (!settlement.close(disposal))
                     failed = true;
             }
             catch {
                 failed = true;
             }
         }
         else if (settlement)
             failed = true;
     }
     if (failed) {
         pending = true;
         throw fixed();
     }
     return true;
 }
 return Object.freeze({admit,target(){if(!ready||pending||closed)throw fixed();return targetValue;},
  recordStopped:(value,limits)=>transition(limits,()=>{if(state.stopped!==null)fail();const stopped=copy(value);exact(stopped,RESTORE);for(const service of RESTORE){const c=stopped[service],originalContainer=targetValue.original.containers[service];exact(c,Object.keys(originalContainer));if(typeof c.startedAt!=='string'||!Number.isFinite(Date.parse(c.startedAt))||Date.parse(c.startedAt)<Date.parse(originalContainer.startedAt)||Date.parse(c.startedAt)>io.now()||!same({...c,startedAt:originalContainer.startedAt},originalContainer))fail();}state.stopped=stopped;}),
  beginRestore:(service,limits)=>transition(limits,()=>{if(!state.stopped)fail();const i=RESTORE.indexOf(service),item=state.restore[i];if(i<0||state.restore.slice(0,i).some(v=>v.phase!=='ACKNOWLEDGED'))fail();phase(item,'READY');item.phase='DISPATCHING';}),
  ackRestore:(service,startedAt,limits)=>transition(limits,()=>{const item=state.restore.find(v=>v.service===service);if(!item||typeof startedAt!=='string'||!Number.isFinite(Date.parse(startedAt))||Date.parse(startedAt)<=Date.parse(state.stopped[service].startedAt)||Date.parse(startedAt)>io.now())fail();phase(item,'DISPATCHING');item.phase='ACKNOWLEDGED';item.startedAt=startedAt;}),
  confirmBaseline:(value,limits)=>transition(limits,()=>{if(state.baseline!==null||state.restore.some(v=>v.phase!=='ACKNOWLEDGED'))fail();state.baseline=proof(value,true);}),
  beginRotation:limits=>transition(limits,()=>{if(!state.baseline)fail();phase(state.rotation,'READY');state.rotation.phase='DISPATCHING';}),
  ackRotation:(version,limits)=>transition(limits,()=>{if(version!==3)fail();phase(state.rotation,'DISPATCHING');state.rotation={phase:'ACKNOWLEDGED',version:3};}),
  beginConfigure:(version,limits)=>transition(limits,()=>{if(version!==3)fail();phase(state.rotation,'ACKNOWLEDGED');phase(state.configure,'READY');state.configure={phase:'DISPATCHING',version:3};}),
  ackConfigure:(version,limits)=>transition(limits,()=>{if(version!==3)fail();phase(state.configure,'DISPATCHING');state.configure={phase:'ACKNOWLEDGED',version:3};}),
  beginRemainder:limits=>operation(limits,async active=>{phase(state.configure,'ACKNOWLEDGED');phase(state.remainder,'READY');if(!state.baseline)fail();state.remainder.phase='DISPATCHING';await save(active);return Object.freeze({...state.remainder});}),
  ackRemainder:(value,limits)=>transition(limits,()=>{const p=exact(value,['refundId']);phase(state.remainder,'DISPATCHING');if(!uuid(p.refundId)||p.refundId===targetValue.priorRefundId)fail();state.remainder.phase='ACKNOWLEDGED';state.remainder.refundId=p.refundId;}),
  confirmRemainder:(value,limits)=>transition(limits,()=>{phase(state.remainder,'ACKNOWLEDGED');const p=proof(value,false);state.remainder.phase='CONFIRMED';state.remainder.providerReference=p.providerReference;state.remainder.proof=p;}),
  confirmFinal:(value,limits)=>transition(limits,()=>{phase(state.remainder,'CONFIRMED');if(state.final!==null)fail();const p=exact(value,['priorPartialVerified','priorCallbackDeliveryVerified','remainderConfirmed','providerVerified','webhookVerified','journalVerified','callbackSignatureVerified','finalTotalVerified']);if(Object.values(p).some(v=>v!==true))fail();state.final=p;}),
  withRetainedAdmin:(callback,limits)=>operation(limits,async active=>{if(typeof callback!=='function'||!admin||await call(active,()=>callback(admin))!==true)fail();return true;}),
  verifyRetainedWebhookSecret:(candidate,limits)=>operation(limits,async()=>{if(typeof candidate!=='string'||!/^whsec_[A-Za-z0-9_]{16,256}$/.test(candidate)||!verifier)fail();const a=Buffer.from(candidate),b=Buffer.from(verifier);if(a.length!==b.length||!timingSafeEqual(a,b))fail();return true;}),close});
}
