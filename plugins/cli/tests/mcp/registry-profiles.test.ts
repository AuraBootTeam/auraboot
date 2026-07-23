import { describe, expect, it } from 'vitest';
import type { ApiClient } from '../../src/client/api-client.js';
import { TOOL_TIERS } from '../../src/mcp/profiles.js';
import { buildToolRegistry } from '../../src/mcp/server.js';

const fakeApi = { get: () => {}, post: () => {} } as unknown as ApiClient;

const namesFor = (opts?: Parameters<typeof buildToolRegistry>[1]) =>
  buildToolRegistry(fakeApi, opts)
    .list()
    .map((t) => t.name);

describe('buildToolRegistry profile scoping', () => {
  it('defaults to the full tool set when no profile is given (backward compatible)', () => {
    const names = namesFor();
    expect(names).toHaveLength(15);
    expect(names).toContain('import_plugin');
  });

  it('read profile exposes only the 10 read/discovery tools', () => {
    const names = namesFor({ profile: 'read' });
    expect(names).toHaveLength(10);
    expect(names).toContain('query_entity');
    expect(names).not.toContain('create_model');
    expect(names).not.toContain('import_plugin');
  });

  it('dsl-authoring profile adds create_* but not import/rollback', () => {
    const names = namesFor({ profile: 'dsl-authoring' });
    expect(names).toHaveLength(13);
    expect(names).toContain('create_model');
    expect(names).toContain('create_command');
    expect(names).not.toContain('import_plugin');
    expect(names).not.toContain('rollback_import');
  });

  it('full profile exposes all 15 tools', () => {
    expect(namesFor({ profile: 'full' })).toHaveLength(15);
  });

  it('every registered tool has a tier (drift guard vs TOOL_TIERS)', () => {
    const missing = namesFor({ profile: 'full' }).filter((n) => !(n in TOOL_TIERS));
    expect(missing).toEqual([]);
  });
});
