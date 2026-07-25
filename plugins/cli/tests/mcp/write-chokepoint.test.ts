import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { WRITE_ROUTES } from '../../src/client/write-routes.js';

const testDir = dirname(fileURLToPath(import.meta.url));
const cliRoot = resolve(testDir, '../..');
const writeToolsDir = join(cliRoot, 'src/mcp/tools/write');

describe('agent-native write chokepoint', () => {
  it('owns every canonical mutation route without changing established paths', () => {
    expect(WRITE_ROUTES.executeCommand('sc:create_showcase')).toBe(
      '/api/meta/commands/execute/sc:create_showcase',
    );
    expect(WRITE_ROUTES.createModel).toBe('/api/meta/models');
    expect(WRITE_ROUTES.createPageSchema).toBe('/api/pages');
    expect(WRITE_ROUTES.createCommand).toBe('/api/meta/commands');
    expect(WRITE_ROUTES.createBindingRule('command/pid')).toBe(
      '/api/meta/commands/command%2Fpid/binding-rules',
    );
    expect(WRITE_ROUTES.deleteCommand('command/pid')).toBe(
      '/api/meta/commands/command%2Fpid',
    );
    expect(WRITE_ROUTES.importPlugin).toBe('/api/plugins/import/execute-direct');
    expect(WRITE_ROUTES.rollbackImport('import/id')).toBe(
      '/api/plugins/import/import%2Fid/rollback',
    );
  });

  it('keeps MCP write tools on ApiClient plus the central route manifest', () => {
    const sources = readdirSync(writeToolsDir)
      .filter((name) => name.endsWith('.ts'))
      .map((name) => [name, readFileSync(join(writeToolsDir, name), 'utf8')] as const);

    for (const [name, source] of sources) {
      expect(source, `${name} must use the central write-route manifest`).toContain(
        'WRITE_ROUTES',
      );
      expect(source, `${name} must not open its own transport`).not.toMatch(
        /\bfetch\s*\(|new\s+ApiClient\s*\(/,
      );
      expect(source, `${name} must not splice an API path into an ApiClient call`).not.toMatch(
        /client\.(?:post|put|delete)\s*\(\s*['"`]\/api\//s,
      );
    }
  });

  it('keeps business command execution on the command pipeline route', () => {
    const source = readFileSync(join(cliRoot, 'src/commands/pipeline/exec.ts'), 'utf8');
    expect(source).toContain('WRITE_ROUTES.executeCommand');
    expect(source).not.toContain('`/api/meta/commands/execute/');
  });
});
