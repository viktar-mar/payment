import {constants} from 'node:fs';
import {lstat, open, realpath} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const directory = fileURLToPath(new URL('.', import.meta.url)).replace(/\/$/, '');
export const sandboxUserFile = directory + '/stripe-sandbox.env.local';
export const stripeKeyNames = Object.freeze([
  'PAYMENT_STRIPE_SANDBOX_SECRET_KEY',
  'PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY',
  'PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET',
]);
const maxBytes = 65536;
function invalid() { throw new Error('sandbox_user_env_invalid'); }

export function validPrivateFile(stat) {
  return stat.isFile() && !stat.isSymbolicLink() && stat.uid === process.getuid()
    && (stat.mode & 0o7777) === 0o600 && stat.nlink === 1
    && Number.isSafeInteger(stat.size) && stat.size >= 0 && stat.size <= maxBytes;
}

async function boundedRead(file, check) {
  check();
  const buffer = Buffer.alloc(maxBytes + 1);
  const {bytesRead} = await file.read(buffer, 0, buffer.length, 0);
  check();
  if (!Number.isInteger(bytesRead) || bytesRead < 0 || bytesRead > maxBytes) invalid();
  return buffer.subarray(0, bytesRead);
}

export async function openSandboxUserEnv(io, check) {
  check();
  const parent = await io.lstat(directory);
  if (!parent.isDirectory() || parent.isSymbolicLink()
      || (parent.uid !== 0 && parent.uid !== process.getuid()) || await io.realpath(directory) !== directory) invalid();
  check();
  const before = await io.lstat(sandboxUserFile);
  if (!validPrivateFile(before) || await io.realpath(sandboxUserFile) !== sandboxUserFile) invalid();
  check();
  const file = await io.open(sandboxUserFile, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    check();
    const stat = await file.stat();
    if (!validPrivateFile(stat) || stat.ino !== before.ino || stat.dev !== before.dev) invalid();
    const bytes = await boundedRead(file, check);
    if (bytes.length !== stat.size) invalid();
    return {file, stat, bytes};
  } catch (error) {
    await file.close();
    throw error;
  }
}

export async function requireSandboxUserEnvUnchanged(original, io, check) {
  check();
  const current = await io.lstat(sandboxUserFile), held = await original.file.stat();
  if (!validPrivateFile(current) || !validPrivateFile(held)
      || current.ino !== original.stat.ino || current.dev !== original.stat.dev
      || held.ino !== original.stat.ino || held.dev !== original.stat.dev
      || await io.realpath(sandboxUserFile) !== sandboxUserFile
      || !(await boundedRead(original.file, check)).equals(original.bytes)) invalid();
}

export function parseSandboxUserEnv(bytes) {
  const text = bytes.toString('utf8');
  if (!Buffer.from(text).equals(bytes) || text.includes('\0')) invalid();
  const assignments = {}, lines = text.split(/(?<=\n)/);
  let webhookIndex = -1;
  for (const [index, line] of lines.entries()) {
    const body = line.replace(/\r?\n$/, '');
    if (/^\s*(?:#.*)?$/.test(body)) continue;
    const match = /^([A-Z_]+)=(?:"([^"\r\n]*)"|'([^'\r\n]*)'|([^\s'"\r\n]*))$/.exec(body);
    if (!match || !stripeKeyNames.includes(match[1]) || Object.hasOwn(assignments, match[1])) invalid();
    assignments[match[1]] = match[2] ?? match[3] ?? match[4];
    if (match[1] === stripeKeyNames[2]) webhookIndex = index;
  }
  const [privateKey, publicKey, webhook] = stripeKeyNames.map(name => assignments[name]);
  if (Object.keys(assignments).length !== 3 || !/^(?:sk|rk)_test_[A-Za-z0-9]+$/.test(privateKey)
      || !/^pk_test_[A-Za-z0-9]+$/.test(publicKey) || (webhook !== '' && !/^whsec_[A-Za-z0-9_]{16,256}$/.test(webhook))
      || Object.values(assignments).some(value => /replace_me|your_key|synthetic|sentinel|example/i.test(value))) invalid();
  return {assignments, lines, webhookIndex};
}

// Admission publishes only success or a fixed refusal. Persistence takes a fresh snapshot at use.
export async function validateFailedRenewalUserEnv(dependencies, check = () => {}) {
  const io = {lstat:dependencies.lstat ?? lstat, open:dependencies.open ?? open, realpath:dependencies.realpath ?? realpath};
  const original = await openSandboxUserEnv(io, check);
  try {
    parseSandboxUserEnv(original.bytes);
    await requireSandboxUserEnvUnchanged(original, io, check);
  } finally {
    await original.file.close();
  }
}
