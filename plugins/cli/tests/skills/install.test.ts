import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  CLIENT_SKILL_DIR,
  checkSkills,
  installSkills,
  listBundledSkills,
  removeSkills,
  resolveClients,
} from '../../src/skills/install.js';

let tmp: string;
let bundleDir: string;
let root: string;

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), 'aura-skills-'));
  bundleDir = join(tmp, 'bundle');
  root = join(tmp, 'workspace');
  // Two bundled skills, each with a SKILL.md.
  for (const name of ['auraboot-data-modeling', 'auraboot-ui-builder']) {
    mkdirSync(join(bundleDir, name), { recursive: true });
    writeFileSync(join(bundleDir, name, 'SKILL.md'), `# ${name}\ncontent for ${name}\n`);
  }
  mkdirSync(root, { recursive: true });
});

afterEach(() => {
  rmSync(tmp, { recursive: true, force: true });
});

describe('listBundledSkills', () => {
  it('lists each skill directory that contains a SKILL.md', () => {
    const names = listBundledSkills(bundleDir)
      .map((s) => s.name)
      .sort();
    expect(names).toEqual(['auraboot-data-modeling', 'auraboot-ui-builder']);
  });
});

describe('resolveClients', () => {
  it('returns all clients for undefined or "all"', () => {
    expect(resolveClients(undefined).sort()).toEqual(['claude', 'codex', 'cursor']);
    expect(resolveClients('all').sort()).toEqual(['claude', 'codex', 'cursor']);
  });

  it('parses a comma-separated subset', () => {
    expect(resolveClients('claude,codex')).toEqual(['claude', 'codex']);
  });

  it('throws on an unknown client', () => {
    expect(() => resolveClients('emacs')).toThrow(/emacs/);
  });
});

describe('installSkills', () => {
  it('copies every bundled skill into each client skills dir', () => {
    const written = installSkills({ bundleDir, root, clients: ['claude'] });
    expect(written).toHaveLength(2);

    const dest = join(root, CLIENT_SKILL_DIR.claude, 'auraboot-data-modeling', 'SKILL.md');
    expect(existsSync(dest)).toBe(true);
    expect(readFileSync(dest, 'utf8')).toContain('content for auraboot-data-modeling');
  });

  it('installs into multiple clients at once', () => {
    installSkills({ bundleDir, root, clients: ['claude', 'codex'] });
    expect(existsSync(join(root, '.claude/skills/auraboot-ui-builder/SKILL.md'))).toBe(true);
    expect(existsSync(join(root, '.agents/skills/auraboot-ui-builder/SKILL.md'))).toBe(true);
  });

  it('overwrites a stale copy on reinstall', () => {
    installSkills({ bundleDir, root, clients: ['claude'] });
    const dest = join(root, '.claude/skills/auraboot-data-modeling/SKILL.md');
    writeFileSync(dest, 'STALE');
    installSkills({ bundleDir, root, clients: ['claude'] });
    expect(readFileSync(dest, 'utf8')).toContain('content for auraboot-data-modeling');
  });
});

describe('checkSkills', () => {
  it('reports not-installed before install', () => {
    const report = checkSkills({ bundleDir, root, clients: ['claude'] });
    expect(report.every((r) => r.installed === false)).toBe(true);
  });

  it('reports installed + upToDate after install, stale after content drift', () => {
    installSkills({ bundleDir, root, clients: ['claude'] });
    let report = checkSkills({ bundleDir, root, clients: ['claude'] });
    expect(report.every((r) => r.installed && r.upToDate)).toBe(true);

    writeFileSync(join(root, '.claude/skills/auraboot-ui-builder/SKILL.md'), 'drifted');
    report = checkSkills({ bundleDir, root, clients: ['claude'] });
    const uiBuilder = report.find((r) => r.skill === 'auraboot-ui-builder')!;
    expect(uiBuilder.installed).toBe(true);
    expect(uiBuilder.upToDate).toBe(false);
  });
});

describe('removeSkills', () => {
  it('removes installed skills and leaves them not-installed', () => {
    installSkills({ bundleDir, root, clients: ['claude'] });
    const removed = removeSkills({ bundleDir, root, clients: ['claude'] });
    expect(removed).toHaveLength(2);
    expect(existsSync(join(root, '.claude/skills/auraboot-data-modeling'))).toBe(false);
    expect(checkSkills({ bundleDir, root, clients: ['claude'] }).every((r) => !r.installed)).toBe(
      true,
    );
  });

  it('is a no-op for skills that were never installed', () => {
    expect(removeSkills({ bundleDir, root, clients: ['cursor'] })).toEqual([]);
  });
});
