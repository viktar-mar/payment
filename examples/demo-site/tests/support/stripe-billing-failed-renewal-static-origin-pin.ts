import {createHmac, createSecretKey, timingSafeEqual} from 'node:crypto';
import {closeSync, constants, fstatSync, lstatSync, openSync, readSync, realpathSync, type Stats} from 'node:fs';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';

const REVIEWED_HMAC_SHA256 = '84a56797dfe7b722644409e4302fcb19434382c5319465c34919a516a64923f7';
const directory = fileURLToPath(new URL('../../../../.superpowers/failed-renewal-static-origin',import.meta.url));
const deny = (_origin:string):boolean=>false;
const sameFile = (a:Stats,b:Stats)=>a.dev===b.dev && a.ino===b.ino;

// Only the dedicated Node test controller uses this policy. The private key is
// independent of all run artifacts and payment credentials.
export function loadFailedRenewalStaticOriginMatcher():(origin:string)=>boolean {
  const bytes = Buffer.alloc(32);
  try {
    const uid = process.getuid?.();
    const privateDirectory = (s:Stats)=>s.uid===uid && s.isDirectory() && (s.mode&0o7777)===0o700;
    const privateKey = (s:Stats)=>s.uid===uid && s.isFile() && s.nlink===1 && (s.mode&0o7777)===0o600 && s.size===bytes.length;
    const parent = lstatSync(directory);
    if(!privateDirectory(parent) || realpathSync(directory)!==directory)return deny;
    const path = join(directory,'policy.key');
    const named = lstatSync(path);
    if(!privateKey(named))return deny;
    const fd = openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW|constants.O_NONBLOCK);
    try {
      const opened = fstatSync(fd);
      if(!privateKey(opened) || !sameFile(named,opened))return deny;
      if(readSync(fd,bytes,0,bytes.length,0)!==bytes.length)return deny;
      const current = lstatSync(path), currentParent = lstatSync(directory);
      if(!privateKey(current) || !sameFile(opened,current) || !privateDirectory(currentParent) ||
        !sameFile(parent,currentParent) || realpathSync(path)!==path)return deny;
    } finally {closeSync(fd);}
    const key = createSecretKey(bytes);
    const reviewed = Buffer.from(REVIEWED_HMAC_SHA256,'hex');
    return origin=>{
      if(typeof origin!=='string' || origin.length>262 || !origin.startsWith('https://'))return false;
      // Compare the caller's exact serialized origin. A path, credential,
      // alternate port or noncanonical spelling changes the authenticated bytes.
      return timingSafeEqual(createHmac('sha256',key).update(origin,'utf8').digest(),reviewed);
    };
  } catch {return deny;}
  finally {bytes.fill(0);}
}
