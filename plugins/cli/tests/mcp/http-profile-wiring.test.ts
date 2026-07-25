import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const testDir = dirname(fileURLToPath(import.meta.url));

describe('Streamable HTTP per-request MCP profile wiring', () => {
  it('resolves x-aura-tools against the startup ceiling before building the registry', () => {
    const source = readFileSync(resolve(testDir, '../../src/mcp/http-server.ts'), 'utf8');

    expect(source).toContain("req.headers['x-aura-tools']");
    expect(source).toContain('resolveRequestMcpProfile(');
    expect(source).toMatch(
      /buildToolRegistry\(client,\s*\{\s*profile:\s*requestProfile\s*\}\)/s,
    );
    expect(source).toContain('error.status');
  });
});
