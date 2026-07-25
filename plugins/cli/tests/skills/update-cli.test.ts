import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { afterEach, describe, expect, it } from 'vitest';

const testDir = dirname(fileURLToPath(import.meta.url));
const cliRoot = resolve(testDir, '../..');
const roots: string[] = [];

afterEach(() => {
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true });
});

describe('aura skills update', () => {
  it('is a real CLI alias that refreshes stale installed skills', () => {
    const root = mkdtempSync(join(tmpdir(), 'aura-skills-update-'));
    roots.push(root);
    const installed = join(
      root,
      '.claude/skills/auraboot-data-modeling/SKILL.md',
    );
    mkdirSync(dirname(installed), { recursive: true });
    writeFileSync(installed, 'STALE\n');

    const update = spawnSync(
      process.execPath,
      [
        '--import',
        'tsx',
        'src/index.ts',
        '--agent-mode',
        'skills',
        'update',
        '--client',
        'claude',
        '--root',
        root,
      ],
      { cwd: cliRoot, encoding: 'utf8' },
    );
    expect(update.status, update.stderr).toBe(0);
    expect(JSON.parse(update.stdout).installed).toHaveLength(6);
    expect(existsSync(installed)).toBe(true);
    expect(readFileSync(installed, 'utf8')).toBe(
      readFileSync(join(cliRoot, 'skills/auraboot-data-modeling/SKILL.md'), 'utf8'),
    );
  });
});
