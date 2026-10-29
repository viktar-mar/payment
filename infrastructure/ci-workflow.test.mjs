import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

// These are bounded policy/coverage checks. actionlint validates actual YAML and
// GitHub Actions semantics; these checks do not attempt to implement a YAML parser.
const readWorkflow = () => readFile(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8');
function jobs(text) {
  const section = text.split(/^jobs:\n/m)[1] ?? '';
  return Object.fromEntries([...section.matchAll(/^  ([\w-]+):\n([\s\S]*?)(?=^  [\w-]+:|$(?![\s\S]))/gm)].map(m => [m[1], m[2]]));
}
const gates = {
  backend: ['node infrastructure/init-local-customer-issuer.mjs', './mvnw -B -ntp verify'],
  frontend: [
    'pnpm --dir frontend install --frozen-lockfile',
    'pnpm --dir frontend/sdk test --run', 'pnpm --dir frontend/sdk typecheck', 'pnpm --dir frontend/sdk build',
    'pnpm --dir frontend/admin test --run', 'pnpm --dir frontend/admin typecheck', 'pnpm --dir frontend/admin lint', 'pnpm --dir frontend/admin build',
    'pnpm --dir examples/demo-site install --frozen-lockfile',
    'pnpm --dir examples/demo-site exec vitest run src/payment-return.test.ts src/subscription-return.test.ts src/vite-return-privacy.test.ts',
    'pnpm --dir examples/demo-site build',
    'pnpm --dir examples/demo-site exec playwright install --with-deps chromium',
    'node --test --test-timeout=60000 --test-concurrency=1 examples/demo-site/tests/support/*.test.ts',
    'pnpm --dir examples/demo-site exec playwright test --config playwright.offline.config.ts --workers=1 --retries=0',
    "run('./mvnw', ['-B', '-ntp', '-DskipTests', 'package']",
    "run('pnpm', ['--dir', 'examples/demo-site', 'exec', 'playwright', 'test', '--config', 'playwright.config.ts', '--workers=1', '--retries=0']",
  ],
  infrastructure: [
    'pnpm --dir frontend install --frozen-lockfile', 'pnpm --dir frontend/sdk build',
    'pnpm --dir examples/demo-site install --frozen-lockfile',
    'docker pull node:24-alpine',
    'node --test --test-timeout=30000 --test-concurrency=1 infrastructure/*.test.mjs',
  ],
};

test('CI admits untrusted PRs with read-only credentials and bounded cancellable jobs', async () => {
  const text = await readWorkflow();
  for (const event of ['push', 'pull_request', 'workflow_dispatch']) assert.match(text, new RegExp(`^  ${event}:`, 'm'));
  assert.match(text, /^permissions:\n  contents: read\n/m);
  assert.equal([...text.matchAll(/^\s*permissions:/gm)].length, 1, 'jobs cannot escalate workflow permissions');
  assert.match(text, /group:.*github.workflow.*github.ref/);
  assert.match(text, /cancel-in-progress: true/);
  assert.doesNotMatch(text, /pull_request_target|paths(?:-ignore)?:|secrets\.|\bif:|continue-on-error|\b(?:deploy|publish|upload-artifact)\b|PAYMENT_STRIPE_SANDBOX_|printenv|\benv\s*$/im);
  // The only env file admitted is an explicitly empty, privately owned file.
  assert.deepEqual([...text.matchAll(/--env-file[^\n]*/g)].map(m => m[0]), ["--env-file', env.PAYMENT_FAKE_CI_EMPTY_ENV,"]);
  const blocks = jobs(text);
  assert.deepEqual(Object.keys(blocks).sort(), ['backend', 'frontend', 'infrastructure']);
  for (const [job, block] of Object.entries(blocks)) {
    assert.match(block, /runs-on: ubuntu-24\.04/);
    assert.match(block, new RegExp(`timeout-minutes: ${job === 'infrastructure' ? 30 : 45}\\b`));
    assert.match(block, /persist-credentials: false/);
    assert.match(block, /node-version: ['"]24['"]/);
    assert.match(block, /working-directory: \./);
    for (const action of block.matchAll(/uses: (\S+)/g)) assert.match(action[1], /^(?:actions\/(?:checkout|setup-node|setup-java)|pnpm\/action-setup)@[a-f0-9]{40}$/);
  }
});

// Dropping the owned launcher, using production java -jar, widening the test
// classpath, omitting the mail owner, or selecting only a browser subset breaks
// the real fixture contract checked here. These remain source policy checks;
// only the hosted job proves YAML composition and runtime behavior.
test('default Fake browser CI supplies the isolated business clock and complete fixture topology', async () => {
  const block = jobs(await readWorkflow()).frontend;
  assert.match(block, /java-version: ['"]25['"]/);
  assert.match(block, /cache: maven\n\s+cache-dependency-path: backend\/pom.xml/);
  const overlay = await readFile(new URL('./compose.fake-e2e.yaml', import.meta.url), 'utf8').catch(error => { if (error.code === 'ENOENT') return ''; throw error; });
  assert.match(overlay, /org\.springframework\.boot\.loader\.launch\.PropertiesLauncher/);
  assert.match(overlay, /-Dloader\.main=dev\.genericpayments\.acceptance\.runtime\.OwnedSubscriptionRuntime/);
  assert.match(overlay, /-Dloader\.path=\/app\/owned-runtime\//);
  assert.match(overlay, /PAYMENT_STAGE4_TEST_RUNTIME: "true"/);
  assert.match(overlay, /PAYMENT_CUSTOMER_EMAIL_OTP_RATE_LIMIT_SOURCE_IP_REQUESTS: "100"/);
  assert.match(overlay, /127\.0\.0\.1:28081:28081/);
  assert.match(overlay, /PAYMENT_FAKE_CI_RUNTIME[^\n]*:\/app\/owned-runtime:ro/);
  assert.match(overlay, /PAYMENT_FAKE_CI_TIME[^\n]*:\/app\/task17-time-fake/);
  assert.match(overlay, /PAYMENT_FAKE_CI_ISSUER[^\n]*:\/app\/local-fixtures\/customer-issuer-private\.jwks\.json:ro/);
  assert.doesNotMatch(overlay, /target\/test-classes|PAYMENT_STRIPE|privileged|network_mode/);
  assert.match(block, /const classes = \['OwnedSubscriptionRuntime\.class', 'OwnedSubscriptionRuntime\$1\.class', 'OwnedSubscriptionRuntime\$BusinessClock\.class'\]/);
  assert.match(block, /org\/springframework\/boot\/loader\/launch\/PropertiesLauncher\.class/);
  assert.match(block, /'infrastructure\/compose\.yaml', '-f', 'infrastructure\/compose\.local-user\.yaml', '-f', 'infrastructure\/compose\.fake-e2e\.yaml'/);
  assert.match(block, /const services = \['postgres', 'mailpit', 'backend', 'admin'\]/);
  assert.match(block, /'up', '--build', '-d', '--wait', '--wait-timeout', '180', \.\.\.services/);
  assert.match(block, /env\.PAYMENT_E2E_MAILPIT_OWNER = owned\.get\('mailpit'\)/);
  assert.match(block, /Authorization: 'Bearer ' \+ env\.PAYMENT_STAGE4_CONTROL_KEY/);
  assert.match(block, /AbortSignal\.timeout\(2000\)/);
  assert.match(block, /Date\.parse\(body\)/);
  assert.match(block, /body\.length > 64/);
  assert.doesNotMatch(block, /PAYMENT_E2E_DEMO_ORIGIN\s*[:=]|--grep|--project|test\.skip|runSmoke|stripe-sandbox-bootstrap|local-compose\.mjs|GITHUB_ENV/);
});

test('Fake CI uses fresh private inputs and stops only captured verified full IDs on every exit', async () => {
  const block = jobs(await readWorkflow()).frontend;
  assert.match(block, /mkdtempSync\(join\(process\.env\.RUNNER_TEMP, 'payment-fake-'\)\)/);
  assert.match(block, /writeFileSync\(env\.PAYMENT_FAKE_CI_EMPTY_ENV, '', \{flag: 'wx', mode: 0o600\}\)/);
  assert.match(block, /initializeLocalCustomerIssuer\(env\.PAYMENT_FAKE_CI_ISSUER\)/);
  assert.match(block, /randomBytes\(32\)\.toString\('base64'\)/);
  assert.match(block, /randomBytes\(32\)\.toString\('base64url'\)/);
  assert.match(block, /encodeURIComponent\(env\.PAYMENT_DB_PASSWORD\)/);
  assert.match(block, /\[25432, 28080, 28025, 21025, 23000, 28081, 4173\]/);
  assert.match(block, /com\.docker\.compose\.project/);
  assert.match(block, /com\.docker\.compose\.service/);
  assert.match(block, /\^\[0-9a-f\]\{64\}\$/);
  assert.match(block, /finally \{[\s\S]*capture\(\);[\s\S]*verify\(service, id\);[\s\S]*\['stop', '--time', '10', id\]/);
  assert.match(block, /process\.exitCode = failure \? 1 : cleanupFailed \? 1 : 0/);
  assert.doesNotMatch(block, /\b(?:down|prune|unlinkSync|rmSync)\b|--remove|docker[^\n]*\brm\b|target\/test-classes[^\n]*:\//);
});

test('Fake telemetry permits every current r0 browser fixture and no retry wildcard', async () => {
  const block = jobs(await readWorkflow()).frontend;
  const labels = (block.match(/const journeys = `([^`]+)`/)?.[1] ?? '').trim().split(/\s+/);
  const expected = `success decline processing unknown failure reload recoverlogout recoverexpiry navigation customer expiry storage racecheckout raceconfirm racehistory racelogout racehistoryerror racelogouterror racebusy scope second pending admin unavailable config delivery donateanon donatecustomer donateinvalid donatemixed donaterevoked donatepolicy donaterace donateoutdraft donateoutintent donateoutok donateouterr adminrefund admincampaign adminredact sub-admin sub-admin-read sub-admin-dialog sub-portal sub-journey sub-locale sub-logout sub-customer-switch sub-disconnect`.split(' ');
  assert.deepEqual(labels.toSorted(), expected.toSorted());
  assert.match(block, /`t12-\$\{env\.PAYMENT_E2E_SITE_RUN\}-\$\{journey\}-r0-site`/);
  const browser = await readFile(new URL('../examples/demo-site/playwright.config.ts', import.meta.url), 'utf8');
  assert.match(browser, /reuseExistingServer: false/);
  for (const ignored of ['stripe-sandbox.spec.ts', 'stripe-sandbox-remaining.spec.ts', 'form-load-diagnostic.spec.ts']) assert.ok(browser.includes(`"${ignored}"`));
  for (const included of ['checkout.spec.ts', 'customer-access.spec.ts', 'subscriptions.spec.ts', 'subscription-session-lifecycle.spec.ts', 'admin-subscriptions.spec.ts']) assert.ok(!browser.includes(`"${included}"`));
});

test('each CI job runs its required gates in dependency order with frozen caches', async () => {
  const blocks = jobs(await readWorkflow());
  for (const [job, commands] of Object.entries(gates)) {
    const block = blocks[job];
    assert.ok(block, `missing ${job} job`);
    let previous = -1;
    for (const command of commands) {
      const position = block.indexOf(command);
      assert.ok(position > previous, `${job}: missing or out-of-order gate ${command}`);
      previous = position;
    }
    assert.match(block, /docker version/);
    assert.match(block, /docker compose version/);
    assert.match(block, /docker buildx version/);
    if (job !== 'backend') {
      assert.ok(block.indexOf('pnpm/action-setup@') < block.indexOf('cache: pnpm'));
      assert.match(block, /version: ['"]11\.1\.3['"]/);
      assert.match(block, /cache-dependency-path: \|\n\s+frontend\/pnpm-lock.yaml\n\s+examples\/demo-site\/pnpm-lock.yaml/);
    }
  }
  assert.match(blocks.backend, /java-version: ['"]25['"]/);
  assert.match(blocks.backend, /cache: maven\n\s+cache-dependency-path: backend\/pom.xml/);
  assert.match(blocks.backend, /working-directory: backend/);
  assert.match(blocks.frontend, /NEXT_TELEMETRY_DISABLED: ['"]1['"]/);
  for (const command of [...(await readWorkflow()).matchAll(/^\s+(?:run: )?(pnpm .*install[^\n]*)/gm)]) {
    if (!command[1].includes('playwright install')) assert.match(command[1], /--frozen-lockfile/);
  }
});
