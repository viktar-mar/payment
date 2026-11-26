import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

const backend = new URL('../backend/', import.meta.url);

test('optional-trial image compiles exactly the source families in the runtime export', async () => {
  const [dockerfile, exporter] = await Promise.all([
    readFile(new URL('Dockerfile.billing-optional-trial', backend), 'utf8'),
    readFile(new URL('export-billing-optional-trial-runtime.sh', backend), 'utf8'),
  ]);
  const buildStage = dockerfile.split(/(?=^FROM )/m).find(stage => stage.startsWith('FROM '));
  assert.ok(buildStage);
  const buildCopies = buildStage.replace(/\\\n\s*/g, ' ').split('\n').map(line => line.trim())
    .filter(line => /^(COPY|ADD)\s/i.test(line))
    .map(line => line.split(/\s+/));
  const groups = [
    ['runtime', 'acceptance/runtime'],
    ['billing', 'platform/provider/adapter/out/stripe/billing'],
  ];
  const javaCopies = groups.map(([inventory, packageDir]) => {
    const directory = 'src/test/java/dev/genericpayments/' + packageDir + '/';
    const classes = new RegExp(`^${inventory}='([^']+)'$`, 'm').exec(exporter)?.[1];
    assert.ok(classes, inventory);
    const sources = classes.split('\n').filter(name => !name.includes('$'))
      .map(name => directory + name.replace(/\.class$/, '.java'));
    return ['COPY', ...sources, './' + directory];
  });
  assert.deepEqual(buildCopies, [
    ['COPY', 'pom.xml', './'],
    ['COPY', 'src/main', './src/main'],
    ...javaCopies,
    ['COPY', 'src/test/java/dev/genericpayments/platform/support/OwnedSubscriptionBillingTime.java',
      './src/test/java/dev/genericpayments/platform/support/'],
    ['COPY', 'src/test/resources/db/owned-optional-trial/', './src/test/resources/db/owned-optional-trial/'],
    ['COPY', 'export-billing-optional-trial-runtime.sh', './'],
  ]);
});
