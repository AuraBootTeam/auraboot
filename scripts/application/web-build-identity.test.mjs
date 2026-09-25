import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runInNewContext } from 'node:vm';
import { ContributionRegistry } from '../../web-admin/app/framework/extensions/contribution-registry.ts';

const root = fileURLToPath(new URL('../../web-admin/', import.meta.url));
const require = createRequire(new URL('../../web-admin/package.json', import.meta.url));
const { build, loadConfigFromFile } = await import(pathToFileURL(require.resolve('vite')).href);
const pin = `sha256:${'a'.repeat(64)}`;
const entry = fileURLToPath(new URL('../../web-admin/app/framework/web-build-identity.ts', import.meta.url));
async function compiled(key) {
  const previousKey = process.env.AURA_COMPOSITION_KEY;
  const previousRoot = process.env.AURA_CORE_WEB_ADMIN_ROOT;
  try {
    if (key === undefined) delete process.env.AURA_COMPOSITION_KEY;
    else process.env.AURA_COMPOSITION_KEY = key;
    process.env.AURA_CORE_WEB_ADMIN_ROOT = root;
    const loaded = await loadConfigFromFile({ command: 'build', mode: 'production' }, `${root}vite.config.ts`, root, 'silent', undefined, 'runner');
    assert.ok(loaded);
    const bundle = await build({
      configFile: false, root, logLevel: 'silent', define: loaded.config.define,
      build: { write: false, minify: false, lib: { entry, formats: ['cjs'] } },
    });
    const outputs = Array.isArray(bundle) ? bundle : [bundle];
    const chunks = outputs.flatMap(output => output.output).filter(output => output.type === 'chunk' && output.isEntry);
    assert.equal(chunks.length, 1);
    const code = chunks[0].code;
    const context = { module: { exports: {} } };
    context.exports = context.module.exports;
    runInNewContext(code, context);
    return context.module.exports.observeBuiltWebContracts;
  } finally {
    if (previousKey === undefined) delete process.env.AURA_COMPOSITION_KEY; else process.env.AURA_COMPOSITION_KEY = previousKey;
    if (previousRoot === undefined) delete process.env.AURA_CORE_WEB_ADMIN_ROOT; else process.env.AURA_CORE_WEB_ADMIN_ROOT = previousRoot;
  }
}

test('actual Vite define embeds a fixed composition stamp and observes the live registry', async () => {
  const observe = await compiled(pin);
  const registry = new ContributionRegistry();
  registry.register('renderer', 'fixture', { id: 'view', component: null, supportedContracts: ['v1'] });
  const requirements = [{ mode: 'exact', kind: 'renderer', key: 'view', contract: 'v1' }];
  const result = observe(registry, requirements, pin);
  assert.equal(result.compositionKey, pin); assert.equal(result.supported, true);
  assert.equal('providerDigests' in result, false); assert.equal('artifactDigest' in result, false);
  assert.throws(() => observe(registry, requirements, `sha256:${'b'.repeat(64)}`), /externally pinned identity/);
  assert.throws(() => observe(registry, requirements, ''), /externally pinned identity/);
  registry.removeByPlugin('fixture');
  assert.equal(observe(registry, requirements, pin).supported, false);
});
test('unmanaged build cannot claim a composition and malformed build identity fails config loading', async () => {
  const observe = await compiled(undefined);
  assert.throws(() => observe(new ContributionRegistry(), [], pin), /externally pinned identity/);
  await assert.rejects(() => compiled('workspace-latest'), /exact SHA-256/);
});
