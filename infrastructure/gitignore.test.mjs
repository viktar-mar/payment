import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readdirSync } from 'node:fs';
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
    'backend/mvnw', 'backend/mvnw.cmd', 'backend/.mvn/wrapper/maven-wrapper.properties',
    'backend/pom.xml', 'backend/src/main/resources/application-local.yml',
    'backend/src/main/resources/db/migration/V20__subscription_transport_receipts_and_payment_links.sql',
    'backend/src/test/resources/features/subscriptions.feature',
    'frontend/pnpm-lock.yaml', 'examples/demo-site/pnpm-lock.yaml',
    'frontend/admin/next-env.d.ts', 'frontend/admin/src/lib/api/schema.d.ts',
    'infrastructure/smoke-stage-4.d.mts', 'docs/local-development.md',
  ]), []);
});
