import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, copyFileSync, chmodSync, symlinkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const wrapper = resolve('backend/mvnw');
const url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip';
const fixture = `#!${process.execPath}
const fs = require('node:fs'), path = require('node:path');
const name = path.basename(process.argv[1]), args = process.argv.slice(2), root = process.env.FIXTURE_ROOT;
const log = (entry) => fs.appendFileSync(path.join(root, 'events'), JSON.stringify({name, ...entry}) + '\\n');
const events = () => fs.existsSync(path.join(root, 'events')) ? fs.readFileSync(path.join(root, 'events'), 'utf8').trim().split('\\n').filter(Boolean).map(JSON.parse) : [];
const config = JSON.parse(fs.readFileSync(path.join(root, 'config'), 'utf8'));
switch (name) {
case 'uname': console.log('Linux'); break;
case 'dirname': console.log(path.dirname(args[0])); break;
case 'basename': console.log(path.basename(args[0])); break;
case 'tr': process.stdout.write(fs.readFileSync(0, 'utf8').replace(/\\s/g, '')); break;
case 'mktemp': { const dir = path.join(root, 'download'); fs.mkdirSync(dir); console.log(dir); break; }
case 'mkdir': for (const arg of args.filter(a => !a.startsWith('-'))) fs.mkdirSync(arg, {recursive:true}); break;
case 'rm': for (const arg of args.filter(a => !a.startsWith('-'))) fs.rmSync(arg, {recursive:true, force:true}); break;
case 'mv': { const paths = args.filter(a => a !== '--'); fs.renameSync(paths[0], paths[1]); break; }
case 'sleep': log({args}); break;
case 'wget': case 'curl': {
  const output = args[args.indexOf(name === 'wget' ? '-O' : '-o') + 1];
  const prior = fs.existsSync(output) ? fs.readFileSync(output, 'utf8') : null;
  const attempt = events().filter(e => e.name === name).length + 1;
  log({args, prior, attempt}); fs.writeFileSync(output, attempt <= config.failures ? 'partial-secret' : 'archive');
  process.stderr.write('RAW_SECRET credential=https://user:password@example.invalid\\n');
  if (name === 'wget' && args.includes('--server-response')) {
    process.stderr.write(config.headers ?? '  HTTP/1.1 302 Found\\n  Location: https://user:password@example.invalid\\n  HTTP/1.1 503 Service Unavailable\\n');
  }
  if (name === 'curl' && args.includes('--write-out')) process.stdout.write(config.httpCode ?? '503\\n');
  process.exit(attempt <= config.failures ? (config.downloadExit ?? 23) : 0); break;
}
case 'unzip': {
  log({args}); if (config.extractionFailure) process.exit(9);
  const destination = args[args.indexOf('-d') + 1];
  const bin = path.join(destination, 'apache-maven-3.9.11', 'bin'); fs.mkdirSync(bin, {recursive:true});
  fs.copyFileSync(path.join(root, 'tool'), path.join(bin, 'mvn')); fs.chmodSync(path.join(bin, 'mvn'), 0o755); break;
}
case 'sha256sum': log({args}); fs.readFileSync(0); process.exit(config.checksumFailure ? 1 : 0); break;
case 'mvn': log({args}); process.exit(config.mavenExit || 0); break;
case 'cat': process.stdout.write(fs.readFileSync(0)); break;
case 'javac': log({args}); break;
case 'java': log({args}); fs.writeFileSync(args.at(-1), 'archive'); break;
default: throw Error('Unprovided fixture tool: ' + name);
}
`;

function setup(options = {}) {
  const root = mkdtempSync(join(tmpdir(), 'mvnw-offline-'));
  const bin = join(root, 'tools'); mkdirSync(bin);
  mkdirSync(join(root, '.mvn/wrapper'), { recursive: true });
  copyFileSync(wrapper, join(root, 'mvnw'));
  writeFileSync(join(root, '.mvn/wrapper/maven-wrapper.properties'), `distributionUrl=${url}\n${options.checksum ? 'distributionSha256Sum=fixture\n' : ''}`);
  writeFileSync(join(root, 'config'), JSON.stringify({ failures: 0, ...options }));
  writeFileSync(join(root, 'tool'), fixture); chmodSync(join(root, 'tool'), 0o755);
  for (const tool of ['uname', 'dirname', 'basename', 'tr', 'mktemp', 'mkdir', 'rm', 'mv', 'sleep', 'unzip', 'sha256sum', 'cat', 'java', 'javac', options.tool || 'wget']) {
    symlinkSync(join(root, 'tool'), join(bin, tool));
  }
  const run = (extraEnv = {}) => {
    const result = spawnSync('/bin/sh', [join(root, 'mvnw'), 'verify', '-Dmessage=two words', '', 'literal;$value'], {
      cwd: root, encoding: 'utf8', timeout: 10000,
      env: { PATH: bin, HOME: root, MAVEN_USER_HOME: join(root, 'm2'), FIXTURE_ROOT: root, ...extraEnv },
    });
    assert.ifError(result.error);
    const events = readFileSync(join(root, 'events'), 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
    return { ...result, events, fetches: events.filter(e => e.name === (options.tool || 'wget')) };
  };
  return { run, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

for (const tool of ['wget', 'curl']) {
  for (const failures of [0, 2, 3]) {
    test(`${tool}: bounded distribution attempts with ${failures} failures`, () => {
      const f = setup({ tool, failures });
      try {
        const r = f.run({ MVNW_VERBOSE: 'true' });
        assert.equal(r.status, failures === 3 ? 1 : 0);
        assert.equal(r.fetches.length, failures === 0 ? 1 : 3);
        assert.deepEqual(r.events.filter(e => e.name === 'sleep').map(e => e.args), failures === 0 ? [] : [['2'], ['4']]);
        for (const fetch of r.fetches) {
          assert.ok(fetch.prior === null || fetch.prior === '', 'each attempt starts with fresh output');
          const required = tool === 'wget' ? ['--tries=1', '--timeout=30'] : ['--retry', '0', '--connect-timeout', '10', '--max-time', '60'];
          for (const flag of required) assert.ok(fetch.args.includes(flag), `missing ${flag}`);
        }
        assert.doesNotMatch(r.stdout + r.stderr, /RAW_SECRET|user:password|partial-secret/);
        for (let attempt = 1; attempt <= Math.min(failures, 3); attempt++) {
          assert.match(r.stderr, new RegExp(tool + '.*' + attempt + '.*23'));
        }
        assert.equal(r.events.filter(e => e.name === 'unzip').length, failures === 3 ? 0 : 1);
        assert.equal(r.events.filter(e => e.name === 'mvn').length, failures === 3 ? 0 : 1);
      } finally { f.cleanup(); }
    });
  }
}

for (const tool of ['wget', 'curl']) {
  test(`${tool}: HTTP rejection reports final status without response text or secrets`, () => {
    const f = setup({tool, failures: 3, downloadExit: tool === 'wget' ? 8 : 22});
    try {
      const r = f.run();
      assert.equal(r.status, 1);
      assert.equal(r.fetches.length, 3);
      assert.equal(r.events.filter(e => e.name === 'unzip' || e.name === 'mvn').length, 0);
      assert.match(r.stderr, /HTTP 503/);
      assert.doesNotMatch(r.stdout + r.stderr, /RAW_SECRET|password|Service Unavailable|Location|302/);
    } finally { f.cleanup(); }
  });

  for (const invalid of ['', '000\n', '503secret\n']) {
    test(`${tool}: absent or invalid HTTP status is UNKNOWN (${JSON.stringify(invalid)})`, () => {
      const f = setup({tool, failures: 3, headers: invalid, httpCode: invalid});
      try {
        const r = f.run();
        assert.equal(r.status, 1);
        assert.match(r.stderr, /HTTP UNKNOWN/);
        assert.doesNotMatch(r.stdout + r.stderr, /RAW_SECRET|password|503secret/);
      } finally { f.cleanup(); }
    });
  }
}

test('Maven receives the exact argument vector and nonzero exit once; cache bypasses fetch', () => {
  const f = setup({ mavenExit: 17 });
  try {
    const first = f.run(); assert.equal(first.status, 17);
    const maven = first.events.filter(e => e.name === 'mvn');
    assert.equal(maven.length, 1); assert.deepEqual(maven[0].args, ['verify', '-Dmessage=two words', '', 'literal;$value']);
    const second = f.run(); assert.equal(second.status, 17);
    assert.equal(second.fetches.length, 1); assert.equal(second.events.filter(e => e.name === 'mvn').length, 2);
    assert.equal(second.events.filter(e => e.name === 'sleep').length, 0);
  } finally { f.cleanup(); }
});

for (const failure of ['extractionFailure', 'checksumFailure']) {
  test(`${failure} remains outside retry`, () => {
    const f = setup({ [failure]: true, checksum: failure === 'checksumFailure' });
    try {
      const r = f.run(); assert.equal(r.status, 1); assert.equal(r.fetches.length, 1);
      assert.equal(r.events.filter(e => e.name === 'sleep' || e.name === 'mvn').length, 0);
      assert.equal(r.events.filter(e => e.name === (failure === 'checksumFailure' ? 'sha256sum' : 'unzip')).length, 1);
      if (failure === 'checksumFailure') assert.equal(r.events.filter(e => e.name === 'unzip').length, 0);
    } finally { f.cleanup(); }
  });
}

test('authenticated distribution retains Java fallback without downloader retries', () => {
  const f = setup();
  try {
    const r = f.run({ MVNW_USERNAME: 'fixture-user', MVNW_PASSWORD: 'fixture-password' });
    assert.equal(r.status, 0); assert.equal(r.fetches.length, 0);
    assert.equal(r.events.filter(e => e.name === 'javac').length, 1);
    assert.equal(r.events.filter(e => e.name === 'java').length, 1);
    assert.equal(r.events.filter(e => e.name === 'sleep').length, 0);
    assert.equal(r.events.filter(e => e.name === 'mvn').length, 1);
  } finally { f.cleanup(); }
});
