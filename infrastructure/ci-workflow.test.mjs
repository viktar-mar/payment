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
    'pnpm --dir examples/demo-site exec vitest run src/payment-return.test.ts src/vite-return-privacy.test.ts',
    'pnpm --dir examples/demo-site build',
    'pnpm --dir examples/demo-site exec playwright install --with-deps chromium',
    'node --test --test-timeout=60000 --test-concurrency=1 examples/demo-site/tests/support/*.test.ts',
    'pnpm --dir examples/demo-site exec playwright test --config playwright.offline.config.ts --workers=1 --retries=0',
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
  assert.doesNotMatch(text, /pull_request_target|paths(?:-ignore)?:|secrets\.|\bif:|continue-on-error|\b(?:deploy|publish|upload-artifact)\b|PAYMENT_STRIPE_SANDBOX_|--env-file|printenv|\benv\s*$/im);
  const blocks = jobs(text);
  assert.deepEqual(Object.keys(blocks).sort(), ['backend', 'frontend', 'infrastructure']);
  for (const [job, block] of Object.entries(blocks)) {
    assert.match(block, /runs-on: ubuntu-24\.04/);
    assert.match(block, new RegExp(`timeout-minutes: ${job === 'backend' ? 45 : 30}\\b`));
    assert.match(block, /persist-credentials: false/);
    assert.match(block, /node-version: ['"]24['"]/);
    assert.match(block, /working-directory: \./);
    for (const action of block.matchAll(/uses: (\S+)/g)) assert.match(action[1], /^(?:actions\/(?:checkout|setup-node|setup-java)|pnpm\/action-setup)@[a-f0-9]{40}$/);
  }
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
