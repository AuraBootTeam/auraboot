import { existsSync, mkdtempSync, readdirSync, rmSync, writeFileSync, readFileSync, mkdirSync } from 'fs';
import { tmpdir } from 'os';
import { join, resolve } from 'path';
import { afterEach, describe, expect, it } from 'vitest';
import { validateStructural } from '../../src/validation/structural.js';
import { loadPlugin } from '../../src/utils/plugin-loader.js';
import type { PluginFiles } from '../../src/utils/plugin-loader.js';

const tempDirs: string[] = [];
const shippedPluginsDir = resolve(import.meta.dirname, '../../../../plugins');
const shippedPluginNames = readdirSync(shippedPluginsDir)
  .filter((name) => existsSync(join(shippedPluginsDir, name, 'plugin.json')))
  .sort();

function pluginWithManifest(manifest: Record<string, unknown>): PluginFiles {
  const dir = mkdtempSync(join(tmpdir(), 'aura-plugin-structural-'));
  tempDirs.push(dir);
  writeFileSync(join(dir, 'plugin.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  return {
    dir,
    configDir: join(dir, 'config'),
    manifest: manifest as PluginFiles['manifest'],
    resourceFiles: new Map(),
  };
}

describe('validateStructural plugin manifest schema', () => {
  afterEach(() => {
    for (const dir of tempDirs.splice(0)) {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  const publicFixture = (type: 'resource' | 'query') => JSON.parse(readFileSync(
    resolve(import.meta.dirname, `../../../../platform/src/testFixtures/resources/plugin/public-${type}-contract.json`), 'utf8'));

  it('validates shared public contract fixtures inline and rejects malformed source pins', () => {
    const manifest = {
      pluginId: 'public-contracts', namespace: 'public_contracts', version: '1.0.0',
      models: [{ code: 'public_record', capabilities: { list: true, detail: true, create: false } }],
      publicResources: [publicFixture('resource')], publicQueries: [publicFixture('query')],
    };
    manifest.publicResources[0].sourceResources = { public_record: 'records.public' };
    expect(validateStructural(pluginWithManifest(manifest)).errorCount).toBe(0);
    manifest.publicResources[0].sourceResources = { public_record: 'private/table' };
    expect(validateStructural(pluginWithManifest(manifest)).messages).toEqual(expect.arrayContaining([
      expect.objectContaining({ code: 'L1-MANIFEST', path: 'plugin.json/publicResources/0/sourceResources/public_record' }),
    ]));
    manifest.publicResources[0].sourceResources = {};
    manifest.publicQueries[0].sourceFingerprint = 'invalid';
    expect(validateStructural(pluginWithManifest(manifest)).messages).toEqual(expect.arrayContaining([
      expect.objectContaining({ code: 'L1-MANIFEST', path: 'plugin.json/publicQueries/0/sourceFingerprint' }),
    ]));
  });

  it.each([false, true])('validates declared public paths in file and directory mode (%s)', directory => {
    const plugin = pluginWithManifest({
      pluginId: 'public-contracts', namespace: 'public_contracts', version: '1.0.0',
      resourceDirs: { publicResources: directory ? 'public-definitions' : 'publication.json' },
    });
    const path = directory ? join(plugin.dir, 'public-definitions', 'records.json') : join(plugin.dir, 'publication.json');
    if (directory) mkdirSync(join(plugin.dir, 'public-definitions'));
    writeFileSync(path, JSON.stringify(directory ? publicFixture('resource') : [publicFixture('resource')]));
    const loaded = loadPlugin(plugin.dir);
    expect(loaded.resourceFiles.get('publicResources')).toEqual([publicFixture('resource')]);
    expect(validateStructural(loaded).errorCount).toBe(0);
    const bad = publicFixture('resource');
    bad.maxPageSize = 10001;
    writeFileSync(path, JSON.stringify(directory ? bad : [bad]));
    expect(validateStructural(loadPlugin(plugin.dir)).messages).toEqual(expect.arrayContaining([
      expect.objectContaining({ code: 'L1-RESOURCE', path: 'config/publicResources.json/0/maxPageSize' }),
    ]));
    writeFileSync(path, '{broken');
    expect(() => loadPlugin(plugin.dir)).toThrow('Invalid public publication file');
    rmSync(path);
    if (!directory) expect(() => loadPlugin(plugin.dir)).toThrow('Required public publication path missing');
  });

  it('accepts the canonical backend jarPath and entryClass fields', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'workflow-demo',
        namespace: 'workflow_demo',
        version: '1.0.0',
        backend: {
          jarPath: 'backend/build/libs/workflow-demo-plugin-1.0.0.jar',
          entryClass: 'com.auraboot.plugins.workflowdemo.WorkflowDemoPlugin',
        },
      }),
    );

    expect(result.messages.filter((message) => message.code === 'L1-MANIFEST')).toEqual([]);
    expect(result.errorCount).toBe(0);
  });

  it('accepts resourceDirs.semantic while keeping unknown resource keys closed', () => {
    const accepted = validateStructural(
      pluginWithManifest({
        pluginId: 'semantic-plugin',
        namespace: 'semantic_plugin',
        version: '1.0.0',
        resourceDirs: {
          semantic: 'config/semantic',
        },
      }),
    );
    expect(accepted.messages.filter((message) => message.code === 'L1-MANIFEST')).toEqual([]);

    const rejected = validateStructural(
      pluginWithManifest({
        pluginId: 'semantic-plugin',
        namespace: 'semantic_plugin',
        version: '1.0.0',
        resourceDirs: {
          semanticUnknown: 'config/semantic',
        },
      }),
    );
    expect(rejected.messages.filter((message) => message.code === 'L1-MANIFEST'))
      .toEqual(expect.arrayContaining([
        expect.objectContaining({
          message: expect.stringContaining('must NOT have additional properties'),
        }),
      ]));
  });

  it('accepts canonical page capabilities, command handlers, and field extraProps', () => {
    const plugin = pluginWithManifest({
      pluginId: 'canonical-runtime-contract',
      namespace: 'canonical_runtime_contract',
      version: '1.0.0',
      provides: [{ type: 'page', code: 'crm_account_360' }],
    });
    plugin.resourceFiles.set('commands', [{
      code: 'crm:rescore_lead',
      modelCode: 'crm_lead_common',
      type: 'action',
      handler: 'crm:rescore_lead',
    }]);
    plugin.resourceFiles.set('fields', [{
      code: 'crm_lead_email',
      dataType: 'string',
      extraProps: { fieldPermission: { view: ['crm_sales'] } },
    }]);

    const result = validateStructural(plugin);

    expect(result.messages.filter((message) => message.code === 'L1-MANIFEST')).toEqual([]);
    expect(result.messages.filter((message) => message.code === 'L1-RESOURCE')).toEqual([]);
    expect(result.errorCount).toBe(0);
  });

  it('accepts preserveInput for runtime-supported auto-set fields', () => {
    const plugin = pluginWithManifest({
      pluginId: 'preserve-input-contract',
      namespace: 'preserve_input_contract',
      version: '1.0.0',
    });
    plugin.resourceFiles.set('commands', [{
      code: 'crm:create_activity',
      modelCode: 'crm_activity_common',
      type: 'create',
      inputFields: ['crm_act_date'],
      autoSetFields: {
        crm_act_date: {
          strategy: 'current_datetime',
          preserveInput: true,
        },
      },
    }]);

    const result = validateStructural(plugin);

    expect(result.messages.filter((message) => message.code === 'L1-RESOURCE')).toEqual([]);
    expect(result.errorCount).toBe(0);
  });

  it('accepts runtime command handler parameters and aggregate concurrency controls', () => {
    const plugin = pluginWithManifest({
      pluginId: 'runtime-command-contract',
      namespace: 'runtime_command_contract',
      version: '1.0.0',
    });
    plugin.resourceFiles.set('commands', [{
      code: 'crm:compile_qdp_revision',
      modelCode: 'crm_qdp_revision_common',
      type: 'custom',
      handler: 'crm:release_qdp',
      handlerParams: {
        dslPersistence: false,
        async: true,
      },
      concurrencyKey: 'crm:qdp:${payload.crm_qdp_customer_request_id}',
      lockTimeoutMs: 8_000,
    }]);

    const result = validateStructural(plugin);

    expect(result.messages.filter((message) => message.code === 'L1-RESOURCE')).toEqual([]);
    expect(result.errorCount).toBe(0);
  });

  it('rejects the removed top-level entryPoint field', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'legacy-plugin',
        namespace: 'legacy',
        version: '1.0.0',
        entryPoint: 'com.acme.LegacyPlugin',
      }),
    );

    expect(result.messages).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'L1-MANIFEST',
          path: 'plugin.json',
        }),
      ]),
    );
    expect(result.errorCount).toBeGreaterThan(0);
  });

  it('rejects the removed top-level clientConfig field', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'legacy-frontend-plugin',
        namespace: 'legacy_frontend',
        version: '1.0.0',
        clientConfig: {
          remoteEntry: 'frontend/remoteEntry.js',
        },
      }),
    );

    expect(result.messages).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'L1-MANIFEST',
          path: 'plugin.json',
        }),
      ]),
    );
    expect(result.errorCount).toBeGreaterThan(0);
  });

  it('rejects removed backend jarFile and entryPoint aliases', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'legacy-plugin',
        namespace: 'legacy',
        version: '1.0.0',
        backend: {
          jarFile: 'backend/legacy.jar',
          entryPoint: 'com.acme.LegacyPlugin',
        },
      }),
    );

    const manifestErrors = result.messages.filter((message) => message.code === 'L1-MANIFEST');
    expect(manifestErrors.every((message) => message.path === 'plugin.json/backend')).toBe(true);
    expect(manifestErrors).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          message: expect.stringContaining("must have required property 'jarPath'"),
        }),
        expect.objectContaining({
          message: expect.stringContaining("must have required property 'entryClass'"),
        }),
        expect.objectContaining({
          message: expect.stringContaining('must NOT have additional properties'),
        }),
      ]),
    );
    expect(result.errorCount).toBeGreaterThan(0);
  });

  it('requires the canonical backend jarPath and entryClass fields together', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'partial-backend-plugin',
        namespace: 'partial_backend',
        version: '1.0.0',
        backend: {
          jarPath: 'backend/partial.jar',
        },
      }),
    );

    const manifestErrors = result.messages.filter((message) => message.code === 'L1-MANIFEST');
    expect(manifestErrors).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: 'plugin.json/backend',
          message: expect.stringContaining("must have required property 'entryClass'"),
        }),
      ]),
    );
    expect(result.errorCount).toBeGreaterThan(0);
  });

  it('accepts field immutability and exact command writer contracts', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'field-writer-contract',
        namespace: 'field_writer_contract',
        version: '1.0.0',
        fields: [{
          code: 'qdp_revision',
          dataType: 'string',
          immutable: true,
          immutableWhen: {
            field: 'status',
            in: ['released', 'superseded'],
          },
          allowedWriterCommands: ['crm:release_qdp', 'crm:supersede_qdp'],
        }],
      }),
    );

    expect(result.messages.filter((message) => message.code === 'L1-MANIFEST')).toEqual([]);
    expect(result.errorCount).toBe(0);
  });

  it('rejects empty or malformed field writer contracts', () => {
    const emptyWriters = validateStructural(
      pluginWithManifest({
        pluginId: 'empty-field-writer-contract',
        namespace: 'empty_field_writer_contract',
        version: '1.0.0',
        fields: [{
          code: 'qdp_revision',
          dataType: 'string',
          allowedWriterCommands: [],
        }],
      }),
    );
    const malformedStateLock = validateStructural(
      pluginWithManifest({
        pluginId: 'malformed-field-state-lock',
        namespace: 'malformed_field_state_lock',
        version: '1.0.0',
        fields: [{
          code: 'qdp_revision',
          dataType: 'string',
          immutableWhen: {
            field: 'status',
            in: [],
          },
        }],
      }),
    );

    expect(emptyWriters.messages.filter((message) => message.code === 'L1-MANIFEST'))
      .toEqual(expect.arrayContaining([
        expect.objectContaining({ message: expect.stringContaining('must NOT have fewer than 1 items') }),
      ]));
    expect(malformedStateLock.messages.filter((message) => message.code === 'L1-MANIFEST'))
      .toEqual(expect.arrayContaining([
        expect.objectContaining({ message: expect.stringContaining('must NOT have fewer than 1 items') }),
      ]));
  });

  it.each(shippedPluginNames)(
    'validates shipped %s resource schemas without schema load warnings',
    (pluginName) => {
      const plugin = loadPlugin(join(shippedPluginsDir, pluginName));

      const result = validateStructural(plugin);

      expect(result.messages.filter((message) => message.code === 'L1-RESOURCE-SCHEMA')).toEqual([]);
      expect(result.messages.filter((message) => message.code === 'L1-RESOURCE')).toEqual([]);
      expect(result.errorCount).toBe(0);
    },
  );

  it('keeps fixture opportunity writers protected by the importable permission contract', () => {
    const plugin = loadPlugin(join(shippedPluginsDir, 'test-fixtures'));
    const commands = plugin.resourceFiles.get('commands')!;
    for (const code of ['e2et_crm_opp.create', 'e2et_crm_opp.update']) {
      const command = commands.find((entry) => entry.code === code);
      expect(command, `${code} must remain present`).toBeDefined();
      expect(command.permissions).toEqual(['e2et.order.manage']);
      expect(command).not.toHaveProperty('execution');
    }
  });
});
