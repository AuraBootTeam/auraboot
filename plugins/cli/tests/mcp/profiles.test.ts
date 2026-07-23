import { describe, expect, it } from 'vitest';
import {
  DEFAULT_MCP_PROFILE,
  filterToolsByProfile,
  resolveMcpProfile,
  toolAllowedInProfile,
} from '../../src/mcp/profiles.js';

describe('resolveMcpProfile', () => {
  it('accepts each known profile name', () => {
    expect(resolveMcpProfile('read')).toBe('read');
    expect(resolveMcpProfile('dsl-authoring')).toBe('dsl-authoring');
    expect(resolveMcpProfile('full')).toBe('full');
  });

  it('defaults to the minimal read profile when unset', () => {
    expect(resolveMcpProfile(undefined)).toBe(DEFAULT_MCP_PROFILE);
    expect(DEFAULT_MCP_PROFILE).toBe('read');
  });

  it('throws a helpful error listing valid names for an unknown profile', () => {
    expect(() => resolveMcpProfile('bogus')).toThrow(/read.*dsl-authoring.*full/);
  });
});

describe('toolAllowedInProfile', () => {
  it('read profile allows read tools only', () => {
    expect(toolAllowedInProfile('query_entity', 'read')).toBe(true);
    expect(toolAllowedInProfile('create_model', 'read')).toBe(false);
    expect(toolAllowedInProfile('import_plugin', 'read')).toBe(false);
  });

  it('dsl-authoring adds author (create_*) tools but not admin tools', () => {
    expect(toolAllowedInProfile('query_entity', 'dsl-authoring')).toBe(true);
    expect(toolAllowedInProfile('create_model', 'dsl-authoring')).toBe(true);
    expect(toolAllowedInProfile('create_command', 'dsl-authoring')).toBe(true);
    expect(toolAllowedInProfile('import_plugin', 'dsl-authoring')).toBe(false);
    expect(toolAllowedInProfile('rollback_import', 'dsl-authoring')).toBe(false);
  });

  it('full allows every tier including admin (import/rollback)', () => {
    expect(toolAllowedInProfile('query_entity', 'full')).toBe(true);
    expect(toolAllowedInProfile('create_model', 'full')).toBe(true);
    expect(toolAllowedInProfile('import_plugin', 'full')).toBe(true);
    expect(toolAllowedInProfile('rollback_import', 'full')).toBe(true);
  });

  it('treats an unknown tool as admin-tier so it never leaks into a narrow profile', () => {
    expect(toolAllowedInProfile('mystery_new_tool', 'read')).toBe(false);
    expect(toolAllowedInProfile('mystery_new_tool', 'dsl-authoring')).toBe(false);
    expect(toolAllowedInProfile('mystery_new_tool', 'full')).toBe(true);
  });
});

describe('filterToolsByProfile', () => {
  it('keeps only tools allowed in the given profile, preserving order', () => {
    const tools = [
      { name: 'query_entity' },
      { name: 'create_model' },
      { name: 'import_plugin' },
    ];
    expect(filterToolsByProfile(tools, 'dsl-authoring').map((t) => t.name)).toEqual([
      'query_entity',
      'create_model',
    ]);
    expect(filterToolsByProfile(tools, 'read').map((t) => t.name)).toEqual(['query_entity']);
    expect(filterToolsByProfile(tools, 'full').map((t) => t.name)).toEqual([
      'query_entity',
      'create_model',
      'import_plugin',
    ]);
  });
});
