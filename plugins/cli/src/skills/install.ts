import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync } from 'fs';
import { join, resolve } from 'path';

/**
 * Absolute path to the skill bundle shipped with this CLI package. Works from
 * both `src/skills` (tsx/dev) and `dist/skills` (compiled) — the bundle sits at
 * the package root in both layouts.
 */
export function resolveBundleDir(): string {
  return resolve(import.meta.dirname, '../../skills');
}

/**
 * End-user Skill bundle installer.
 *
 * Ships intent-based Skills with the CLI and installs them into each agent
 * client's skills directory (mirrors NocoBase's `nb skills install`). Skills
 * are the discovery / workflow layer — they tell a customer's Codex / Claude
 * Code / Cursor how to drive AuraBoot via the `aura` CLI; they never execute
 * writes themselves.
 */

export const SKILL_CLIENTS = ['claude', 'cursor', 'codex'] as const;
export type SkillClient = (typeof SKILL_CLIENTS)[number];

/** Where each client discovers project-scoped skills (relative to the workspace root). */
export const CLIENT_SKILL_DIR: Record<SkillClient, string> = {
  claude: '.claude/skills',
  cursor: '.cursor/skills',
  codex: '.agents/skills',
};

export interface SkillInfo {
  name: string;
  dir: string;
  skillFile: string;
}

export interface InstalledSkill {
  client: SkillClient;
  skill: string;
  dest: string;
}

export interface SkillCheck {
  client: SkillClient;
  skill: string;
  installed: boolean;
  upToDate: boolean;
}

/** List the bundled skills (each is a directory containing a SKILL.md). */
export function listBundledSkills(bundleDir: string): SkillInfo[] {
  if (!existsSync(bundleDir)) return [];
  return readdirSync(bundleDir, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => ({
      name: e.name,
      dir: join(bundleDir, e.name),
      skillFile: join(bundleDir, e.name, 'SKILL.md'),
    }))
    .filter((s) => existsSync(s.skillFile));
}

/** Resolve a `--client` selector to a concrete client list. */
export function resolveClients(sel: string | undefined): SkillClient[] {
  if (sel === undefined || sel === '' || sel === 'all') return [...SKILL_CLIENTS];
  const parts = sel
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  for (const p of parts) {
    if (!(SKILL_CLIENTS as readonly string[]).includes(p)) {
      throw new Error(
        `Unknown skills client "${p}". Valid clients: ${SKILL_CLIENTS.join(', ')}, all.`,
      );
    }
  }
  return parts as SkillClient[];
}

/** Copy every bundled skill into each client's skills dir (overwrites). */
export function installSkills(opts: {
  bundleDir: string;
  root: string;
  clients: SkillClient[];
}): InstalledSkill[] {
  const skills = listBundledSkills(opts.bundleDir);
  const written: InstalledSkill[] = [];
  for (const client of opts.clients) {
    for (const skill of skills) {
      const destDir = join(opts.root, CLIENT_SKILL_DIR[client], skill.name);
      mkdirSync(destDir, { recursive: true });
      const dest = join(destDir, 'SKILL.md');
      cpSync(skill.skillFile, dest);
      written.push({ client, skill: skill.name, dest });
    }
  }
  return written;
}

/** Report install / up-to-date status for each (client, skill) pair. */
export function checkSkills(opts: {
  bundleDir: string;
  root: string;
  clients: SkillClient[];
}): SkillCheck[] {
  const skills = listBundledSkills(opts.bundleDir);
  const out: SkillCheck[] = [];
  for (const client of opts.clients) {
    for (const skill of skills) {
      const dest = join(opts.root, CLIENT_SKILL_DIR[client], skill.name, 'SKILL.md');
      const installed = existsSync(dest);
      const upToDate =
        installed && readFileSync(dest, 'utf8') === readFileSync(skill.skillFile, 'utf8');
      out.push({ client, skill: skill.name, installed, upToDate });
    }
  }
  return out;
}

/** Remove installed copies of bundled skills for the given clients. Returns removed dirs. */
export function removeSkills(opts: {
  bundleDir: string;
  root: string;
  clients: SkillClient[];
}): string[] {
  const skills = listBundledSkills(opts.bundleDir);
  const removed: string[] = [];
  for (const client of opts.clients) {
    for (const skill of skills) {
      const dir = join(opts.root, CLIENT_SKILL_DIR[client], skill.name);
      if (existsSync(dir)) {
        rmSync(dir, { recursive: true, force: true });
        removed.push(dir);
      }
    }
  }
  return removed;
}
