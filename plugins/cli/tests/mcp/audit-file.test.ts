import { chmodSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { _internal, type AuditEntry } from '../../src/mcp/audit.js';

let directory: string;
const entry: AuditEntry = { ts: 'fixture', tool: 'models', tenantId: 7, durationMs: 1, success: true,
  error: 'untrusted\nsecond line' };
beforeEach(() => { directory = mkdtempSync(join(tmpdir(), 'mcp-private-audit-')); });
afterEach(() => { rmSync(directory, { recursive: true, force: true }); });

it('appends JSON data privately and preserves existing journal entries', () => {
  const file = join(directory, 'mcp-audit.log');
  _internal.writeLocal(entry, directory);
  chmodSync(file, 0o644);
  _internal.writeLocal(entry, directory);
  expect(statSync(file).mode & 0o777).toBe(0o600);
  const lines = readFileSync(file, 'utf8').trim().split('\n');
  expect(lines).toHaveLength(2);
  expect(lines.map(line => JSON.parse(line))).toEqual([entry, entry]);
});

it('refuses a symlink without altering its target or mode', () => {
  const target = join(directory, 'preserved');
  writeFileSync(target, 'preserved', { mode: 0o640 });
  const before = statSync(target).mode;
  symlinkSync(target, join(directory, 'mcp-audit.log'));
  _internal.writeLocal(entry, directory);
  expect(readFileSync(target, 'utf8')).toBe('preserved');
  expect(statSync(target).mode).toBe(before);
});
