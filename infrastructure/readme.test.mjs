import test from 'node:test';
import assert from 'node:assert/strict';
import {copyFile, lstat, mkdir, mkdtemp, readFile, readlink, realpath, rm, stat, symlink, writeFile} from 'node:fs/promises';
import {execFile} from 'node:child_process';
import {tmpdir} from 'node:os';
import {isAbsolute, join, relative, resolve, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {promisify} from 'node:util';

const root = fileURLToPath(new URL('../', import.meta.url));
const execute = promisify(execFile);
const readReadme = () => readFile(join(root, 'README.md'), 'utf8');
const shellCommands = text => [...text.matchAll(/^```(?:bash|sh)\n([\s\S]*?)^```/gm)]
  .flatMap(match => match[1].split('\n')).map(line => line.trim()).filter(line => line && !line.startsWith('#'));

async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'readme-quick-start-'));
  t.after(() => rm(dir, {recursive: true, force: true}));
  await mkdir(join(dir, 'infrastructure'));
  // Copy only public source files, never ignored local configuration or issuer keys.
  for (const file of ['.env.example', 'init-local-customer-issuer.mjs']) {
    await copyFile(join(root, 'infrastructure', file), join(dir, 'infrastructure', file));
  }
  return dir;
}

async function preparationCommands() {
  const commands = shellCommands(await readReadme());
  const initializer = commands.find(line => /^node\s+/.test(line));
  assert.ok(initializer, 'quick start must invoke the local issuer CLI');
  const match = /^node (infrastructure\/[a-z-]+\.mjs)$/.exec(initializer);
  assert.ok(match, 'only a repository Node entrypoint may be executed in the fixture');
  assert.equal(match[1], 'infrastructure/init-local-customer-issuer.mjs');
  await stat(join(root, match[1]));
  const copy = commands.find(line => /\bcp\b/.test(line));
  assert.ok(copy, 'quick start must provide a guarded environment copy');
  // Allow the guard's behavior to fail below, but never execute arbitrary README shell text.
  assert.match(copy, /^(?:\[ (?:! )?-(?:e|f|L) infrastructure\/\.env \] (?:\|\||&&) )*cp infrastructure\/\.env\.example infrastructure\/\.env$/);
  assert.ok(commands.indexOf(initializer) < commands.indexOf(copy));
  assert.ok(commands.indexOf(copy) < commands.findIndex(line => /^node infrastructure\/local-compose\.mjs up$/.test(line)),
    'issuer and environment preparation must precede startup');
  return {script: match[1], copy};
}

test('entry links resolve inside the repository, including coverage and roadmap anchors', async () => {
  const text = await readReadme();
  const links = [...text.matchAll(/\[[^\]]+\]\(([^\s)]+)\)/g)].map(match => match[1])
    .filter(link => !/^[a-z][a-z\d+.-]*:/i.test(link));
  for (const required of ['docs/local-development.md', 'frontend/README.md', 'frontend/sdk/README.md', 'examples/demo-site/',
    '.github/workflows/ci.yml', 'infrastructure/.env.example', 'infrastructure/compose.yaml', 'infrastructure/init-local-customer-issuer.mjs',
    'docs/local-development.md#secret-free-ci-coverage',
    'docs/superpowers/specs/2026-10-14-stripe-sandbox-design.md#1-последовательность-этапа']) {
    assert.ok(links.includes(required), `missing navigation to ${required}`);
  }
  const repository = await realpath(root);
  for (const link of links) {
    const [path, anchor] = decodeURI(link).split('#');
    const target = await realpath(resolve(root, path || 'README.md'));
    const within = relative(repository, target);
    assert.ok(!isAbsolute(within) && within !== '..' && !within.startsWith(`..${sep}`), `link escapes repository: ${link}`);
    if (anchor) {
      const headings = [...(await readFile(target, 'utf8')).matchAll(/^#{1,6}\s+(.+)$/gm)]
        .map(match => match[1].toLowerCase().replace(/[^\p{L}\p{N}_\s-]/gu, '').replace(/\s/g, '-'));
      assert.ok(headings.includes(anchor), `missing heading target: ${link}`);
    }
  }
});

test('fresh-clone preparation creates usable local files and preserves them on repeat', async t => {
  const {script, copy} = await preparationCommands();
  const dir = await fixture(t);
  await execute(process.execPath, [script], {cwd: dir});
  await execute('/bin/sh', ['-c', copy], {cwd: dir});
  const env = join(dir, 'infrastructure', '.env');
  const issuer = join(dir, 'infrastructure', 'fixtures', 'customer-issuer-private.jwks.json');
  assert.deepEqual(await readFile(env), await readFile(join(dir, 'infrastructure', '.env.example')));
  const keys = await readFile(issuer);
  assert.equal(JSON.parse(keys).keys.length, 2);
  assert.equal((await lstat(issuer)).mode & 0o777, 0o600);
  await execute(process.execPath, [script], {cwd: dir});
  await execute('/bin/sh', ['-c', copy], {cwd: dir});
  assert.deepEqual(await readFile(issuer), keys);
});

test('environment copy preserves existing regular configuration', async t => {
  const {copy} = await preparationCommands();
  const dir = await fixture(t);
  const env = join(dir, 'infrastructure', '.env');
  const original = 'synthetic existing configuration\n';
  await writeFile(env, original, {mode: 0o600});
  await execute('/bin/sh', ['-c', copy], {cwd: dir});
  assert.equal(await readFile(env, 'utf8'), original);
  assert.equal((await lstat(env)).mode & 0o777, 0o600);
});

for (const dangling of [false, true]) {
  test(`environment copy preserves ${dangling ? 'dangling' : 'regular-target'} symlinks`, async t => {
    const {copy} = await preparationCommands();
    const dir = await fixture(t);
    const env = join(dir, 'infrastructure', '.env');
    const target = join(dir, 'synthetic-target');
    if (!dangling) await writeFile(target, 'synthetic target\n');
    await symlink(target, env);
    await execute('/bin/sh', ['-c', copy], {cwd: dir});
    assert.equal(await readlink(env), target);
    if (dangling) await assert.rejects(lstat(target), {code: 'ENOENT'});
    else assert.equal(await readFile(target, 'utf8'), 'synthetic target\n');
  });
}

test('startup and stop commands use the local non-root launcher and retain volumes', async () => {
  const commands = shellCommands(await readReadme()).filter(line => /\b(?:up|stop|down)\b/.test(line));
  assert.equal(commands.length, 2, 'quick start needs one startup and one stop command');
  for (const [index, command] of commands.entries()) {
    const tokens = command.split(/\s+/);
    assert.deepEqual(tokens, ['node', 'infrastructure/local-compose.mjs', index === 0 ? 'up' : 'stop']);
    await stat(join(root, tokens[1]));
  }
});
