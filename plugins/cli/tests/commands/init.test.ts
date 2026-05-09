import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, existsSync, readFileSync, mkdirSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { initCommand, resolveInitOptions } from '../../src/commands/init.js';

/**
 * Tests for `aura plugin init` non-interactive mode (F-5).
 *
 * The interactive code path is intentionally not exercised here — it depends
 * on inquirer prompting from a TTY which is awkward to mock. The contract we
 * verify is:
 *   1. `--non-interactive` skips inquirer entirely (no hang, deterministic).
 *   2. `--dir` controls output location.
 *   3. Missing/invalid flags fail loudly with an actionable error message
 *      that lists every offending flag.
 *   4. Backwards-compat: `initCommand(name)` with no options still resolves
 *      the same default-derivation logic that interactive mode used to.
 */
describe('aura plugin init — non-interactive mode (F-5)', () => {
  let workDir: string;

  beforeEach(() => {
    workDir = mkdtempSync(join(tmpdir(), 'aura-init-test-'));
  });

  afterEach(() => {
    rmSync(workDir, { recursive: true, force: true });
  });

  describe('happy path', () => {
    it('creates plugin at --dir without prompting', async () => {
      const target = join(workDir, 'my-plugin');
      const result = await initCommand('my-plugin', {
        dir: target,
        nonInteractive: true,
        pluginId: 'com.acme.demo',
        namespace: 'demo',
        displayName: 'Demo Plugin',
        pluginType: 'config',
      });

      expect(result.targetDir).toBe(target);
      expect(existsSync(join(target, 'plugin.json'))).toBe(true);

      const manifest = JSON.parse(readFileSync(join(target, 'plugin.json'), 'utf8'));
      expect(manifest.pluginId).toBe('com.acme.demo');
      expect(manifest.namespace).toBe('demo');
      expect(manifest.displayName).toBe('Demo Plugin');
      expect(manifest.pluginType).toBe('config');
    });

    it('defaults to includeSampleModel=true when --no-sample-model is not given', async () => {
      const target = join(workDir, 'with-sample');
      await initCommand('with-sample', {
        dir: target,
        nonInteractive: true,
        pluginId: 'com.acme.with',
        namespace: 'withx',
        pluginType: 'config',
      });
      expect(existsSync(join(target, 'config', 'models.json'))).toBe(true);
      const models = JSON.parse(readFileSync(join(target, 'config', 'models.json'), 'utf8'));
      expect(models.length).toBe(1);
    });

    it('honors --no-sample-model (noSampleModel:true)', async () => {
      const target = join(workDir, 'empty-plugin');
      await initCommand('empty-plugin', {
        dir: target,
        nonInteractive: true,
        pluginId: 'com.acme.empty',
        namespace: 'empty',
        pluginType: 'hybrid',
        noSampleModel: true,
      });
      const models = JSON.parse(readFileSync(join(target, 'config', 'models.json'), 'utf8'));
      expect(models).toEqual([]);
    });

    it('derives sensible defaults from name when only --non-interactive is set', async () => {
      const target = join(workDir, 'auto-derived');
      const result = await initCommand('auto-derived', {
        dir: target,
        nonInteractive: true,
      });
      expect(result.options.pluginId).toBe('com.mycompany.auto-derived');
      expect(result.options.namespace).toBe('autoderive');
      expect(result.options.displayName).toBe('Auto Derived');
      expect(result.options.pluginType).toBe('config');
      expect(result.options.includeSampleModel).toBe(true);
    });
  });

  describe('error path', () => {
    it('throws with all invalid flags listed when pluginId/namespace are malformed', async () => {
      await expect(
        initCommand('bad', {
          dir: join(workDir, 'bad'),
          nonInteractive: true,
          pluginId: 'NOT_VALID_ID', // uppercase rejected
          namespace: '1starts-with-digit', // must start with letter
          pluginType: 'config',
        }),
      ).rejects.toThrow(/--plugin-id.*--namespace/s);
    });

    it('throws on invalid pluginType', async () => {
      await expect(
        resolveInitOptions('x', {
          nonInteractive: true,
          pluginId: 'com.acme.x',
          namespace: 'x',
          // @ts-expect-error — deliberately invalid
          pluginType: 'unknown',
        }),
      ).rejects.toThrow(/--plugin-type/);
    });

    it('throws when --dir already exists', async () => {
      const target = join(workDir, 'collision');
      mkdirSync(target);
      await expect(
        initCommand('collision', {
          dir: target,
          nonInteractive: true,
          pluginId: 'com.acme.c',
          namespace: 'col',
          pluginType: 'config',
        }),
      ).rejects.toThrow(/already exists/);
    });
  });

  describe('backwards compatibility', () => {
    it('resolveInitOptions in non-interactive mode mirrors prior default logic', async () => {
      // Pre-F-5 interactive defaults were:
      //   pluginId    = `com.mycompany.<name>`
      //   namespace   = first 10 chars of [a-z0-9]
      //   displayName = title-cased name with spaces
      //   pluginType  = first choice = 'config'
      //   sample      = true
      const opts = await resolveInitOptions('hello-world', { nonInteractive: true });
      expect(opts).toEqual({
        pluginId: 'com.mycompany.hello-world',
        namespace: 'helloworld',
        displayName: 'Hello World',
        pluginType: 'config',
        includeSampleModel: true,
      });
    });
  });
});
