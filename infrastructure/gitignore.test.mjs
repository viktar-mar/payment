import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));

function ignoredPaths(paths) {
  const result = spawnSync('git', ['check-ignore', '--no-index', '-z', '--stdin'], {
    cwd: root,
    encoding: 'utf8',
    input: paths.join('\0') + '\0',
    env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' },
    maxBuffer: 4 * 1024 * 1024,
  });
  assert.ok(result.status === 0 || result.status === 1, result.stderr);
  return result.stdout.split('\0').filter(Boolean).sort();
}

// Walk the filesystem rather than Git's inventory: that inventory hid the
// original regression by silently omitting application/port/out and adapter/out.
function sourceFiles(directory) {
  return readdirSync(new URL(directory + '/', `file://${root}`), { withFileTypes: true })
    .flatMap(entry => {
      const path = `${directory}/${entry.name}`;
      assert.equal(entry.isSymbolicLink(), false, `unexpected source symlink: ${path}`);
      return entry.isDirectory() ? sourceFiles(path) : [path];
    });
}

test('all actual backend and frontend source files remain publishable', () => {
  const directories = [
    'backend/src', 'frontend/sdk/src', 'frontend/admin/src',
    'examples/demo-site/src', 'examples/demo-site/tests',
  ];
  for (const directory of directories) {
    const paths = sourceFiles(directory);
    assert.ok(paths.length > 0, `source inventory must not be empty: ${directory}`);
    assert.deepEqual(ignoredPaths(paths), [], `source files excluded in ${directory}`);
  }
});

test('hexagonal outbound packages are source, not build output', () => {
  assert.deepEqual(ignoredPaths([
    'backend/src/main/java/dev/genericpayments/platform/shared/adapter/out/time/TimeConfiguration.java',
    'backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/PaymentProviderPort.java',
    'backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/fake/FakePaymentProviderAdapter.java',
    'backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/StripeAdapterTest.java',
  ]), []);
});

test('local credentials and generated outputs remain ignored', () => {
  const paths = [
    '.env', 'infrastructure/.env', 'backend/.env', 'frontend/admin/.env.local',
    'examples/demo-site/.env.production', 'infrastructure/stripe-sandbox.env.local',
    'backend/stripe-sandbox.env.local', 'frontend/admin/stripe-sandbox.env.local',
    'infrastructure/fixtures/customer-issuer-private.jwks.json',
    'infrastructure/fixtures/customer-issuer-public.jwks.json',
    'secrets/stripe.json', 'credentials/oauth.json', '.aws/credentials', '.stripe/config.toml',
    'infrastructure/stripe.key', 'infrastructure/tls.pem',
    'out/index.html', 'frontend/admin/out/index.html',
    'backend/target/classes/App.class', 'frontend/admin/.next/server/app.js',
    'frontend/sdk/dist/index.js', 'examples/demo-site/dist/index.html',
    'frontend/node_modules/package/index.js', 'frontend/admin/tsconfig.tsbuildinfo',
    'examples/demo-site/test-results/.last-run.json', 'examples/demo-site/playwright-report/index.html',
    'examples/demo-site/traces/browser.zip', 'examples/demo-site/screenshots/auth.png',
    'examples/demo-site/videos/auth.webm', '.superpowers/evidence.json',
  ];
  assert.deepEqual(ignoredPaths(paths), paths.toSorted());
});

test('templates, build configuration, migrations and declarations remain publishable', () => {
  assert.deepEqual(ignoredPaths([
    '.gitignore', '.dockerignore', 'infrastructure/gitignore.test.mjs',
    'infrastructure/.env.example', 'frontend/admin/.env.example',
    'infrastructure/stripe-sandbox.env.example', 'infrastructure/compose.yaml',
    'infrastructure/compose.stripe-sandbox.yaml', 'infrastructure/init-stripe-sandbox-env.mjs',
    'infrastructure/stripe-sandbox-config.test.mjs',
    'backend/mvnw', 'backend/mvnw.cmd', 'backend/.mvn/wrapper/maven-wrapper.properties',
    'backend/pom.xml', 'backend/src/main/resources/application-local.yml',
    'backend/src/main/resources/db/migration/V20__subscription_transport_receipts_and_payment_links.sql',
    'backend/src/test/resources/features/subscriptions.feature',
    'frontend/pnpm-lock.yaml', 'examples/demo-site/pnpm-lock.yaml',
    'frontend/admin/next-env.d.ts', 'frontend/admin/src/lib/api/schema.d.ts',
    'infrastructure/smoke-stage-4.d.mts', 'docs/local-development.md',
  ]), []);
});

// Use Docker's actual context filter on synthetic fixtures, never the real
// workspace context (which may contain private local configuration).
for (const context of ['.', 'backend', 'frontend']) {
  test(`${context} Docker context excludes local Sandbox keys and issuer fixtures but retains build inputs`, () => {
    const directory = mkdtempSync(join(tmpdir(), 'payment-docker-context-'));
    try {
      const input = join(directory, 'input');
      const output = join(directory, 'output');
      mkdirSync(input);
      writeFileSync(join(input, '.dockerignore'), readFileSync(join(root, context, '.dockerignore')));
      const excluded = [
        'stripe-sandbox.env.local', 'nested/stripe-sandbox.env.local', 'nested/.env.local',
        'infrastructure/fixtures/customer-issuer-private.jwks.json',
        'infrastructure/fixtures/customer-issuer-public.jwks.json',
      ];
      const retained = [
        'stripe-sandbox.env.example', 'nested/stripe-sandbox.env.example', '.env.example',
        'src/main/java/example/application/port/out/PaymentProviderPort.java',
        'src/main/java/example/adapter/out/stripe/StripeAdapter.java',
        'src/index.ts', 'mvnw', '.mvn/wrapper/maven-wrapper.properties', 'pnpm-lock.yaml',
      ];
      for (const path of [...excluded, ...retained]) {
        mkdirSync(dirname(join(input, path)), { recursive: true });
        writeFileSync(join(input, path), 'synthetic context fixture\n');
      }
      writeFileSync(join(input, 'Dockerfile'), 'FROM scratch\nCOPY . /\n');
      const result = spawnSync('docker', [
        'buildx', 'build', '--network=none', '--progress=quiet',
        '--output', `type=local,dest=${output}`, input,
      ], { encoding: 'utf8', timeout: 25_000, maxBuffer: 1024 * 1024 });
      assert.equal(result.status, 0, result.stderr);
      for (const path of excluded) assert.equal(existsSync(join(output, path)), false, `private context path: ${path}`);
      for (const path of retained) assert.equal(existsSync(join(output, path)), true, `missing build input: ${path}`);
    } finally { rmSync(directory, { recursive: true }); }
  });
}
