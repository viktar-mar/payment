import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const source = join(root, 'infrastructure/local-compose.mjs');
function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'payment-local-compose-'));
  t.after(() => rmSync(dir, {recursive: true, force: true}));
  return dir;
}
async function builder() {
  assert.ok(existsSync(source), 'local Compose launcher must exist');
  return (await import(pathToFileURL(source))).buildLocalComposeInvocation;
}

test('launcher derives validated local identity and fixes Compose paths independently of cwd', async () => {
  const build = await builder();
  const result = build(['up'], {platform: 'linux', uid: 1000, gid: 1000,
    env: {PAYMENT_LOCAL_UID: '0', PAYMENT_LOCAL_GID: '0', PUBLIC_MARKER: 'retained'}});
  assert.equal(result.command, 'docker');
  assert.equal(result.cwd, root);
  assert.deepEqual(result.args, ['compose', '--env-file', join(root, 'infrastructure/.env'),
    '-f', join(root, 'infrastructure/compose.yaml'), '-f', join(root, 'infrastructure/compose.local-user.yaml'),
    'up', '--build', '-d', '--wait']);
  assert.deepEqual(result.env, {PAYMENT_LOCAL_UID: '1000', PAYMENT_LOCAL_GID: '1000', PUBLIC_MARKER: 'retained'});
  const stop = build(['stop'], {platform: 'darwin', uid: 501, gid: 20, env: {}});
  assert.deepEqual(stop.args.slice(-1), ['stop']);
  assert.ok(!stop.args.some(arg => ['down', '-v', '--volumes', '--build'].includes(arg)));
});

test('root, unsupported platforms and invalid identities are rejected before Docker', async () => {
  const build = await builder();
  for (const identity of [
    {platform: 'linux', uid: 0, gid: 1000}, {platform: 'win32', uid: 1000, gid: 1000},
    ...[-1, 1.5, NaN, Infinity, '1000', undefined, 4294967295].flatMap(value => [
      {platform: 'linux', uid: value, gid: 1000}, {platform: 'linux', uid: 1000, gid: value},
    ]),
  ]) assert.throws(() => build(['up'], {...identity, env: {}}), /local|POSIX|UID|GID|root/i);
  assert.equal(build(['stop'], {platform: 'linux', uid: 1000, gid: 0, env: {}}).env.PAYMENT_LOCAL_GID, '0');
});

test('only exact up and stop operations are accepted', async () => {
  const build = await builder();
  for (const args of [[], ['down'], ['config'], ['up', '--build'], ['stop', '-v'], ['--help'], ['up', 'backend']]) {
    assert.throws(() => build(args, {platform: 'linux', uid: 1000, gid: 1000, env: {}}), /usage|up|stop/i);
  }
});

function copiedLauncher(t) {
  assert.ok(existsSync(source), 'local Compose launcher must exist');
  const dir = fixture(t);
  const repo = join(dir, 'copied-repository');
  mkdirSync(join(repo, 'infrastructure'), {recursive: true});
  const script = join(repo, 'infrastructure/local-compose.mjs');
  copyFileSync(source, script);
  const bin = join(dir, 'bin');
  mkdirSync(bin);
  const capture = join(dir, 'docker-call.json');
  const fake = join(dir, 'docker-fake.mjs');
  writeFileSync(fake, `import('node:fs').then(fs => {
    fs.writeFileSync(${JSON.stringify(capture)}, JSON.stringify({args: process.argv.slice(2), cwd: process.cwd(),
      uid: process.env.PAYMENT_LOCAL_UID, gid: process.env.PAYMENT_LOCAL_GID}));
    process.exit(Number(process.env.FAKE_DOCKER_EXIT ?? 0));
  });\n`);
  const quote = value => `'${value.replaceAll("'", "'\\''")}'`;
  writeFileSync(join(bin, 'docker'), `#!/bin/sh\nexec ${quote(process.execPath)} -- ${quote(fake)} "$@"\n`, {mode: 0o700});
  const env = {PATH: bin, PAYMENT_LOCAL_UID: '0', PAYMENT_LOCAL_GID: '0', PRIVATE_CANARY: 'do-not-print'};
  const run = args => spawnSync(process.execPath, [script, ...args], {cwd: dir, env, encoding: 'utf8', timeout: 5000});
  return {dir, repo, script, capture, env, run};
}

test('import is inert and CLI invokes Docker from another cwd with derived IDs', t => {
  const f = copiedLauncher(t);
  const imported = spawnSync(process.execPath, ['--input-type=module', '-e', `await import(${JSON.stringify(pathToFileURL(f.script).href)})`],
    {cwd: f.dir, env: f.env, encoding: 'utf8', timeout: 5000});
  assert.equal(imported.status, 0, imported.stderr);
  assert.equal(imported.stdout + imported.stderr, '');
  assert.equal(existsSync(f.capture), false);
  const run = f.run(['up']);
  assert.equal(run.status, 0, run.stderr);
  assert.equal(run.stdout + run.stderr, '');
  const call = JSON.parse(readFileSync(f.capture, 'utf8'));
  const repo = realpathSync(f.repo);
  assert.equal(call.cwd, repo);
  assert.equal(call.uid, String(process.getuid()));
  assert.equal(call.gid, String(process.getgid()));
  assert.deepEqual(call.args, ['compose', '--env-file', join(repo, 'infrastructure/.env'),
    '-f', join(repo, 'infrastructure/compose.yaml'), '-f', join(repo, 'infrastructure/compose.local-user.yaml'),
    'up', '--build', '-d', '--wait']);
});

test('CLI rejects extra arguments without spawning and propagates Docker failure safely', t => {
  const f = copiedLauncher(t);
  const invalid = f.run(['up', '--anything']);
  assert.equal(invalid.status, 1);
  assert.equal(existsSync(f.capture), false);
  assert.doesNotMatch(invalid.stderr, /do-not-print/);
  f.env.FAKE_DOCKER_EXIT = '37';
  assert.equal(f.run(['stop']).status, 37);
  f.env.PATH = join(f.dir, 'missing-bin');
  const absent = f.run(['up']);
  assert.equal(absent.status, 1);
  assert.equal(absent.stdout, '');
  assert.doesNotMatch(absent.stderr, /do-not-print|PRIVATE_CANARY/);
  assert.match(absent.stderr, /Docker|docker/);
});

// Break caught: UID10001 cannot read a host UID1000 fixture with mode0600.
// Exercise kernel ownership inside Linux, never Docker Desktop bind translation.
test('rendered local backend identity reads owner0600 fixture while UID10001 is denied', t => {
  const dir = fixture(t);
  const docker = process.env.PAYMENT_TEST_DOCKER_BINARY ?? 'docker';
  const env = {PATH: process.env.PATH, HOME: dir, DOCKER_CONFIG: join(dir, 'docker'), BUILDX_CONFIG: join(dir, 'buildx')};
  if (process.env.PAYMENT_TEST_DOCKER_HOST) env.DOCKER_HOST = process.env.PAYMENT_TEST_DOCKER_HOST;
  mkdirSync(env.DOCKER_CONFIG);
  const syntheticEnv = join(dir, 'synthetic.env');
  writeFileSync(syntheticEnv, [
    'PAYMENT_DB_PASSWORD=synthetic-password', 'PAYMENT_DATA_ENCRYPTION_KEY=synthetic-key',
    'PAYMENT_DONATION_MESSAGE_FINGERPRINT_KEY=synthetic-fingerprint', 'PAYMENT_CUSTOMER_DIGEST_KEY_V1=synthetic-digest',
    'PAYMENT_CUSTOMER_HISTORY_CURSOR_KEY_V1=synthetic-cursor', 'PAYMENT_ADMIN_BOOTSTRAP_USERNAME=synthetic-admin',
    'PAYMENT_ADMIN_BOOTSTRAP_PASSWORD=synthetic-password', 'PAYMENT_LOCAL_UID=1000', 'PAYMENT_LOCAL_GID=1000', '',
  ].join('\n'), {mode: 0o600});
  const args = ['compose', '--env-file', syntheticEnv, '-f', join(root, 'infrastructure/compose.yaml')];
  const overlay = join(root, 'infrastructure/compose.local-user.yaml');
  // Before the fix, demonstrate the known backend Dockerfile USER payment=10001.
  if (existsSync(overlay)) args.push('-f', overlay);
  const config = spawnSync(docker, [...args, 'config', '--format=json'], {cwd: dir, env, encoding: 'utf8', timeout: 20000});
  assert.equal(config.status, 0, config.stderr);
  const services = JSON.parse(config.stdout).services;
  const [uid, gid] = (services.backend.user ?? '10001:10001').split(':').map(Number);
  if (existsSync(overlay)) {
    const base = spawnSync(docker, [...args.slice(0, -2), 'config', '--format=json'], {cwd: dir, env, encoding: 'utf8', timeout: 20000});
    assert.equal(base.status, 0, base.stderr);
    const expected = JSON.parse(base.stdout).services;
    expected.backend.user = '1000:1000';
    assert.deepEqual(services, expected, 'overlay must change only backend user');
    const original = readFileSync(syntheticEnv, 'utf8');
    for (const key of ['PAYMENT_LOCAL_UID', 'PAYMENT_LOCAL_GID']) {
      writeFileSync(syntheticEnv, original.replace(new RegExp(`^${key}=.*$`, 'm'), ''));
      const missing = spawnSync(docker, [...args, 'config', '--quiet'], {cwd: dir, env, encoding: 'utf8', timeout: 20000});
      assert.notEqual(missing.status, 0, `${key} must be required`);
      assert.match(missing.stderr, new RegExp(key));
    }
  }
  assert.equal(services.backend.environment.PAYMENT_PROVIDER, 'fake');
  const container = `payment-local-permissions-${process.pid}-${Date.now()}`;
  const script = `
    const fs = require('node:fs');
    const {generateKeyPairSync} = require('node:crypto');
    const {spawnSync} = require('node:child_process');
    const path = '/fixture/issuer.json';
    const key = generateKeyPairSync('ec', {namedCurve: 'P-256'}).privateKey.export({format:'jwk'});
    fs.writeFileSync(path, JSON.stringify({keys:[key]}), {mode:0o600});
    fs.chownSync(path, 1000, 1000);
    const probe = 'const fs=require("node:fs"); try { const v=JSON.parse(fs.readFileSync("/fixture/issuer.json","utf8")); process.exit(v.keys.length===1?0:3) } catch(e) { process.exit(e.code==="EACCES"?13:2) }';
    const selected=spawnSync(process.execPath,['-e',probe],{uid:${uid},gid:${gid}});
    const denied=spawnSync(process.execPath,['-e',probe],{uid:10001,gid:10001});
    const st=fs.statSync(path);
    console.log(JSON.stringify({uid:st.uid,gid:st.gid,mode:st.mode&511,selected:selected.status,denied:denied.status}));
  `;
  t.after(() => {
    const cleanup = spawnSync(docker, ['rm', '-f', container], {env, encoding: 'utf8', timeout: 10000});
    assert.ok(cleanup.status === 0 || /No such container/.test(cleanup.stderr), cleanup.stderr);
  });
  const run = spawnSync(docker, ['run', '--rm', '--pull=never', '--name', container, '--network=none', '--read-only',
    '--tmpfs', '/fixture:rw,noexec,nosuid,size=1m,mode=0755', '--user', '0:0', '-i', 'node:24-alpine', 'node'],
  {input: script, cwd: dir, env, encoding: 'utf8', timeout: 20000});
  assert.equal(run.status, 0, run.stderr);
  assert.deepEqual(JSON.parse(run.stdout), {uid: 1000, gid: 1000, mode: 0o600, selected: 0, denied: 13});
  t.diagnostic(`Linux owner1000:1000 mode0600; selected ${uid}:${gid} read=0; UID10001 read=13 (EACCES)`);
  assert.equal(`${uid}:${gid}`, '1000:1000');
  for (const [name, service] of Object.entries(services)) {
    if (name !== 'backend') assert.equal(service.user, undefined, `${name} identity unexpectedly overridden`);
  }
});
