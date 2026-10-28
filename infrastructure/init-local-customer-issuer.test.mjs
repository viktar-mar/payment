import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, copyFile, readFile, writeFile, lstat, readdir, rm, symlink} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createPrivateKey, createPublicKey, sign, verify} from 'node:crypto';

const execute = promisify(execFile);
const source = new URL('./init-local-customer-issuer.mjs', import.meta.url);
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'local-issuer-test-'));
  t.after(() => rm(dir, {recursive: true, force: true}));
  return dir;
}
async function initialize(file) {
  const {initializeLocalCustomerIssuer} = await import(source);
  return initializeLocalCustomerIssuer(file);
}

test('importing a copied helper creates no fixture or output', async t => {
  const dir = await fixture(t);
  const script = join(dir, 'init-local-customer-issuer.mjs');
  await copyFile(source, script);
  const result = await execute(process.execPath, ['--input-type=module', '-e', `await import(${JSON.stringify(pathToFileURL(script).href)})`], {cwd: dir});
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, '');
  assert.deepEqual(await readdir(dir), ['init-local-customer-issuer.mjs']);
});

test('creates owner-only usable RSA-2048 and P-256 private signing keys', async t => {
  const file = join(await fixture(t), 'nested', 'issuer.json');
  assert.equal(await initialize(file), 'created');
  assert.equal((await lstat(file)).mode & 0o777, 0o600);
  const {keys} = JSON.parse(await readFile(file, 'utf8'));
  assert.equal(keys.length, 2);
  for (const [index, kid, alg, type] of [[0, 'local-fixture-rs', 'RS256', 'rsa'], [1, 'local-fixture-es', 'ES256', 'ec']]) {
    const jwk = keys[index];
    assert.equal(jwk.kid, kid);
    assert.equal(jwk.alg, alg);
    assert.equal(jwk.use, 'sig');
    const key = createPrivateKey({key: jwk, format: 'jwk'});
    assert.equal(key.asymmetricKeyType, type);
    if (type === 'rsa') assert.equal(key.asymmetricKeyDetails.modulusLength, 2048);
    else assert.equal(jwk.crv, 'P-256');
    const data = Buffer.from('synthetic local issuer proof');
    assert.equal(verify('sha256', data, createPublicKey(key), sign('sha256', data, key)), true);
  }
});

test('preserves existing regular fixture bytes without parsing private data', async t => {
  const file = join(await fixture(t), 'issuer.json');
  const bytes = Buffer.from('existing opaque fixture\n');
  await writeFile(file, bytes);
  assert.equal(await initialize(file), 'preserved');
  assert.deepEqual(await readFile(file), bytes);
});

test('rejects directories and symlinks including a dangling symlink', async t => {
  const dir = await fixture(t);
  const target = join(dir, 'target');
  await writeFile(target, 'untouched');
  await symlink(target, join(dir, 'link'));
  await symlink(join(dir, 'missing'), join(dir, 'dangling'));
  await mkdir(join(dir, 'directory'));
  for (const name of ['link', 'dangling', 'directory']) {
    await assert.rejects(initialize(join(dir, name)), /regular file/);
  }
  assert.equal(await readFile(target, 'utf8'), 'untouched');
  assert.equal((await lstat(join(dir, 'link'))).isSymbolicLink(), true);
});

test('concurrent creators never overwrite the winning fixture', async t => {
  const dir = await fixture(t);
  const file = join(dir, 'issuer.json');
  const child = `import {initializeLocalCustomerIssuer as init} from ${JSON.stringify(source.href)}; console.log(init(${JSON.stringify(file)}));`;
  const results = await Promise.all(Array.from({length: 4}, () => execute(process.execPath, ['--input-type=module', '-e', child])));
  assert.equal(results.filter(r => r.stdout.trim() === 'created').length, 1);
  assert.equal(results.filter(r => r.stdout.trim() === 'preserved').length, 3);
  const bytes = await readFile(file);
  assert.equal(JSON.parse(bytes).keys.length, 2);
  assert.equal(await initialize(file), 'preserved');
  assert.deepEqual(await readFile(file), bytes);
});

test('CLI uses its own repository from another cwd and reports only fixed safe status', async t => {
  const dir = await fixture(t);
  const root = join(dir, 'copied-repository');
  await mkdir(join(root, 'infrastructure'), {recursive: true});
  const script = join(root, 'infrastructure', 'init-local-customer-issuer.mjs');
  await copyFile(source, script);
  const file = join(root, 'infrastructure', 'fixtures', 'customer-issuer-private.jwks.json');
  const first = await execute(process.execPath, [script], {cwd: dir});
  assert.equal(first.stdout, 'Local-only issuer fixture created.\n');
  assert.equal(first.stderr, '');
  const bytes = await readFile(file);
  const second = await execute(process.execPath, [script], {cwd: dir});
  assert.equal(second.stdout, 'Existing local issuer fixture preserved.\n');
  assert.equal(second.stderr, '');
  assert.deepEqual(await readFile(file), bytes);
  await rm(file);
  await symlink(join(dir, 'private-target'), file);
  await assert.rejects(execute(process.execPath, [script], {cwd: dir}), error => {
    assert.equal(error.code, 1);
    assert.equal(error.stdout, '');
    assert.equal(error.stderr, 'Unable to initialize local issuer fixture.\n');
    return true;
  });
});
