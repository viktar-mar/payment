import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs, { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const sandboxKeys = {
  PAYMENT_STRIPE_SANDBOX_SECRET_KEY: 'sk_test_synthetic_config_only',
  PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY: 'pk_test_synthetic_config_only',
  PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET: 'whsec_synthetic_config_only',
};
const baseEnvironment = {
  PAYMENT_DB_PASSWORD: 'synthetic-database-password',
  PAYMENT_DATA_ENCRYPTION_KEY: 'synthetic-encryption-key',
  PAYMENT_DONATION_MESSAGE_FINGERPRINT_KEY: 'synthetic-fingerprint-key',
  PAYMENT_CUSTOMER_DIGEST_KEY_V1: 'synthetic-digest-key',
  PAYMENT_CUSTOMER_HISTORY_CURSOR_KEY_V1: 'synthetic-cursor-key',
  PAYMENT_ADMIN_BOOTSTRAP_USERNAME: 'synthetic-admin',
  PAYMENT_ADMIN_BOOTSTRAP_PASSWORD: 'synthetic-admin-password',
};

function withDirectory(run) {
  const directory = mkdtempSync(join(tmpdir(), 'payment-stripe-config-'));
  try { return run(directory); } finally { rmSync(directory, { recursive: true }); }
}

async function initializer() {
  return (await import('./init-stripe-sandbox-env.mjs')).initializeSandboxEnv;
}

test('private env is exclusive, mode 0600 and never overwritten', async () => {
  const initializeSandboxEnv = await initializer();
  withDirectory(directory => {
    const path = join(directory, 'stripe-sandbox.env.local');
    assert.equal(initializeSandboxEnv(path), 'created');
    assert.equal(statSync(path).mode & 0o777, 0o600);
    const original = readFileSync(path);
    assert.equal(initializeSandboxEnv(path), 'preserved');
    assert.deepEqual(readFileSync(path), original);
  });
});

test('existing custom bytes and permissions are preserved with a safe warning', async t => {
  const initializeSandboxEnv = await initializer();
  withDirectory(directory => {
    const path = join(directory, 'stripe-sandbox.env.local');
    const original = Buffer.from('private-local-value\n\0');
    writeFileSync(path, original, { mode: 0o600 });
    chmodSync(path, 0o640);
    const warnings = [];
    t.mock.method(console, 'warn', value => warnings.push(value));
    assert.equal(initializeSandboxEnv(path), 'preserved');
    assert.deepEqual(readFileSync(path), original);
    assert.equal(statSync(path).mode & 0o777, 0o640);
    assert.equal(warnings.length, 1);
    assert.match(warnings[0], /permissions.*0600/i);
    assert.ok(!warnings[0].includes('private-local-value'));
    assert.ok(!warnings[0].includes(path));
  });
});

test('symlinks are rejected including dangling links without following their targets', async () => {
  const initializeSandboxEnv = await initializer();
  withDirectory(directory => {
    const target = join(directory, 'target');
    writeFileSync(target, 'private-target', { mode: 0o600 });
    for (const [name, destination] of [['linked', target], ['dangling', join(directory, 'absent')]]) {
      const path = join(directory, name);
      symlinkSync(destination, path);
      assert.throws(() => initializeSandboxEnv(path), /regular file/i);
    }
    assert.equal(readFileSync(target, 'utf8'), 'private-target');
    assert.throws(() => initializeSandboxEnv(directory), /regular file/i);
  });
});

test('new private env contains exactly three empty Sandbox keys and disabled Billing actions', async () => {
  const initializeSandboxEnv = await initializer();
  withDirectory(directory => {
    const path = join(directory, 'stripe-sandbox.env.local');
    initializeSandboxEnv(path);
    const contents = readFileSync(path, 'utf8');
    assert.deepEqual(contents.split('\n').filter(line => line && !line.startsWith('#')), [
      'PAYMENT_STRIPE_SANDBOX_SECRET_KEY=',
      'PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY=',
      'PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET=',
      'PAYMENT_STRIPE_BILLING_ACTIONS_ENABLED=false',
    ]);
    assert.match(contents, /заполнить локально; не передавать в чат/);
  });
});

test('exclusive creation rechecks a concurrently created regular file or symlink', async t => {
  const initializeSandboxEnv = await initializer();
  withDirectory(directory => {
    for (const kind of ['regular', 'symlink']) {
      const path = join(directory, kind);
      const actualLstat = fs.lstatSync;
      const lstat = t.mock.method(fs, 'lstatSync', candidate => {
        try { return actualLstat(candidate); } catch (error) {
          if (error.code !== 'ENOENT' || candidate !== path) throw error;
          // Create the competing path after the first absence check, before
          // the real exclusive write. The kernel must produce EEXIST.
          if (kind === 'regular') writeFileSync(path, 'concurrent-value', { mode: 0o600 });
          else symlinkSync(join(directory, 'missing-target'), path);
          throw error;
        }
      });
      syncBuiltinESMExports();
      try {
        if (kind === 'regular') {
          assert.equal(initializeSandboxEnv(path), 'preserved');
          assert.equal(readFileSync(path, 'utf8'), 'concurrent-value');
        } else assert.throws(() => initializeSandboxEnv(path), /regular file/i);
      } finally {
        lstat.mock.restore();
        syncBuiltinESMExports();
      }
    }
  });
});

test('CLI uses its module directory and prints status without file contents', () => {
  withDirectory(directory => {
    const moduleDirectory = join(directory, 'module');
    const otherDirectory = join(directory, 'other');
    mkdirSync(moduleDirectory);
    mkdirSync(otherDirectory);
    for (const name of ['init-stripe-sandbox-env.mjs', 'stripe-sandbox.env.example']) {
      writeFileSync(join(moduleDirectory, name), readFileSync(new URL(name, import.meta.url)));
    }
    const run = () => spawnSync(process.execPath, [join(moduleDirectory, 'init-stripe-sandbox-env.mjs')], {
      cwd: otherDirectory, encoding: 'utf8', timeout: 5_000,
    });
    const created = run();
    assert.equal(created.status, 0);
    assert.equal(created.stdout, 'created\n');
    assert.equal(created.stderr, '');
    const path = join(moduleDirectory, 'stripe-sandbox.env.local');
    assert.equal(statSync(path).mode & 0o777, 0o600);
    assert.equal(existsSync(join(otherDirectory, 'stripe-sandbox.env.local')), false);
    writeFileSync(path, 'private-cli-value');
    const preserved = run();
    assert.equal(preserved.status, 0);
    assert.equal(preserved.stdout, 'preserved\n');
    assert.equal(preserved.stderr, '');
    assert.equal(readFileSync(path, 'utf8'), 'private-cli-value');
  });
});

function compose(directory, environment, overlay = true, quiet = false) {
  const envFile = join(directory, 'synthetic.env');
  writeFileSync(envFile, Object.entries(environment).map(([key, value]) => `${key}=${value}\n`).join(''), { mode: 0o600 });
  const args = ['compose', '--env-file', envFile, '-f', 'infrastructure/compose.yaml'];
  if (overlay) args.push('-f', 'infrastructure/compose.stripe-sandbox.yaml');
  args.push('config', quiet ? '--quiet' : '--format=json');
  return spawnSync('docker', args, {
    cwd: root, encoding: 'utf8', timeout: 20_000, maxBuffer: 1024 * 1024,
    // Deliberately do not inherit PAYMENT_*, COMPOSE_* or DOTENV settings.
    env: { PATH: process.env.PATH, HOME: directory, DOCKER_CONFIG: directory },
  });
}

test('opt-in Compose binds Sandbox keys only to backend and preserves default Fake', () => {
  withDirectory(directory => {
    const base = compose(directory, baseEnvironment, false);
    assert.equal(base.status, 0, 'base Compose must render without Stripe keys');
    const baseServices = JSON.parse(base.stdout).services;
    assert.equal(baseServices.backend.environment.PAYMENT_PROVIDER, 'fake');
    assert.equal(baseServices.backend.environment.PAYMENT_STRIPE_SANDBOX_ENABLED, undefined);
    const result = compose(directory, { ...baseEnvironment, ...sandboxKeys });
    assert.equal(result.status, 0, 'Sandbox Compose must render with synthetic keys');
    const services = JSON.parse(result.stdout).services;
    const backend = services.backend.environment;
    assert.equal(backend.PAYMENT_PROVIDER, 'fake');
    assert.equal(backend.PAYMENT_STRIPE_SANDBOX_ENABLED, 'true');
    assert.equal(backend.PAYMENT_CUSTOMER_LOCAL_ISSUER_ENABLED, 'true');
    const profiles = backend.SPRING_PROFILES_ACTIVE.split(',');
    assert.ok(profiles.length > 0 && profiles.every(profile => ['local', 'test'].includes(profile)),
      'Sandbox profiles must admit the enabled local issuer and local email capability');
    for (const [key, value] of Object.entries(sandboxKeys)) assert.equal(backend[key], value);
    for (const [name, service] of Object.entries(services)) {
      if (name === 'backend') continue;
      assert.ok(!JSON.stringify(service).includes('PAYMENT_STRIPE_SANDBOX'), `${name} received Sandbox bindings`);
      for (const value of Object.values(sandboxKeys)) {
        assert.ok(!JSON.stringify(service).includes(value), `${name} received a Sandbox value`);
      }
    }
  });
});

test('opt-in Compose fails closed for each absent or empty Sandbox key', () => {
  withDirectory(directory => {
    for (const key of Object.keys(sandboxKeys)) {
      for (const value of [undefined, '']) {
        const environment = { ...baseEnvironment, ...sandboxKeys };
        if (value === undefined) delete environment[key]; else environment[key] = value;
        const result = compose(directory, environment, true, true);
        assert.notEqual(result.status, 0, `${key} must be required`);
        assert.match(result.stderr, new RegExp(key));
        assert.equal(result.stdout, '');
      }
    }
  });
});
