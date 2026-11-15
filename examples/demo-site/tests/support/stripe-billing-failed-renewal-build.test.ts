// HOSTED ONLY. Build the deployed entry and inspect its artifacts without opening the page.
import assert from 'node:assert/strict';
import {mkdtemp, readFile, readdir, rm, stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {build} from 'vite';

test('failed-renewal delivery bundles both real SDK mounts and its public client on the dedicated page', async () => {
  const root = fileURLToPath(new URL('../..', import.meta.url));
  const sdk = resolve(root, '../../frontend/sdk/src');
  const privateDir = await mkdtemp(join(tmpdir(), 'failed-renewal-build-'));
  const outDir = join(privateDir, 'dist');
  const modules = new Set<string>();
  try {
    await build({
      root, configFile: join(root, 'vite.config.ts'), configLoader: 'runner', envFile: false,
      mode: 'billing-failed-renewal', logLevel: 'silent', cacheDir: join(privateDir, 'cache'),
      build: {outDir},
      plugins: [{
        name: 'inspect-failed-renewal-delivery',
        generateBundle(_options, bundle) {
          const entry = this.getModuleInfo(join(root, 'src/billing-failed-renewal.ts'));
          assert.ok(entry?.importedIds.includes(join(sdk, 'index.ts')), 'entry uses the current public SDK source');
          for (const output of Object.values(bundle)) {
            if (output.type === 'chunk') for (const id of output.moduleIds) modules.add(id);
          }
        },
      }],
    });
    assert.deepEqual((await readdir(outDir)).sort(), ['assets', 'billing-failed-renewal.html', 'health']);
    assert.equal(await readFile(join(outDir, 'health'), 'utf8'), 'ok\n');
    const html = await readFile(join(outDir, 'billing-failed-renewal.html'), 'utf8');
    const entry = /<script\b[^>]*\bsrc="\/(assets\/[\w.-]+\.js)"/.exec(html)?.[1];
    assert.ok(entry, 'delivered HTML points to its emitted JavaScript');
    const file = join(outDir, entry);
    const size = (await stat(file)).size;
    assert.ok(size > 0 && size < 5 * 1024 * 1024);
    for (const name of ['client.ts', 'subscriptions/stripe-payment-attempt-driver.ts', 'subscriptions/stripe-subscription-method.ts']) {
      assert.ok(modules.has(join(sdk, name)), `deployed chunks include ${name}`);
    }
    assert.ok(modules.has(join(root, 'src/billing-failed-renewal-page.ts')));
  } finally {
    await rm(privateDir, {recursive: true, force: true});
  }
});
