#!/usr/bin/env node
// Form back-link reachability checker.
//
// Why this gate exists (2026-07-22, production Quote/BOM):
//
// The header of every kind:form page renders a "返回" link. Its default target is
// `/p/{urlPrefix}`, which the router turns into the pageKey `{urlPrefix}_list`. That
// default is right for a CRUD form reached from its own list page and wrong for every
// other kind of form:
//
//   - a command-entry form (`/p/bom_start_conversion/new`) whose URL prefix is a pageKey,
//     not a model — there is no `bom_start_conversion_list` page, so the link errored out;
//   - a custom-route form (`/p/c/enterprise_info_form`) whose prefix is the whole pageKey;
//   - a singleton settings form reached straight from a top-level menu, which has no parent.
//
// Nothing caught it: DSL validation passes, the import validator returns success, the page
// renders perfectly, and the link only breaks when a human clicks it. The static audits are
// structurally blind because reachability is a property of the *page set*, not of one page.
//
// The rule: a form page is clean when either the derived `{prefix}_list` page exists, or the
// page declares `extension.backTo` — a pageKey, an absolute path, or the literal "none" for
// a page with no parent. A declared pageKey target must itself exist, so a typo fails here
// instead of in front of a user.
//
// Self-contained (node: builtins only) so it vendors per-repo and runs under a CI single-repo
// checkout, exactly like scripts/check-raw-labels.mjs and scripts/check-docs-governance.mjs.
//
// Usage:
//   node scripts/check-form-back-link.mjs [--root <dir>]... [--json] [--quiet]
// Exit: 0 = clean, 1 = unreachable back link(s), 2 = config/IO failure.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SKIP_DIRS = new Set(['node_modules', '.git', 'build', 'dist', '.worktrees', 'archive']);

/** Targets that point outside the scanned page set — each needs a reason. */
const ALLOWLIST_FILE = 'check-form-back-link.allow.json';

/** Collect every pages.json / config/pages/*.json under a root. */
export function findPageFiles(root, out = []) {
  let entries;
  try {
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries) {
    if (SKIP_DIRS.has(entry.name)) continue;
    const full = path.join(root, entry.name);
    if (entry.isDirectory()) {
      findPageFiles(full, out);
    } else if (entry.isFile() && entry.name.endsWith('.json')) {
      const parent = path.basename(path.dirname(full));
      if (entry.name === 'pages.json' || parent === 'pages') out.push(full);
    }
  }
  return out;
}

/** Read the page array out of a pages file, tolerating both shapes. */
function readPages(file) {
  const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (Array.isArray(parsed)) return parsed;
  if (parsed && Array.isArray(parsed.pages)) return parsed.pages;
  if (parsed && parsed.pageKey) return [parsed];
  return [];
}

/**
 * Resolve what a declared `backTo` target must exist as.
 *
 * @returns {{kind: 'none'} | {kind: 'pageKey', pageKey: string} | {kind: 'external'}}
 */
export function classifyBackTo(target) {
  const trimmed = String(target ?? '').trim();
  if (!trimmed) return { kind: 'external' };
  if (trimmed === 'none') return { kind: 'none' };
  if (trimmed.startsWith('/p/c/')) {
    const pageKey = trimmed.slice('/p/c/'.length).split(/[/?#]/)[0];
    return pageKey ? { kind: 'pageKey', pageKey } : { kind: 'external' };
  }
  if (trimmed.startsWith('/p/')) {
    const prefix = trimmed.slice('/p/'.length).split(/[/?#]/)[0];
    return prefix ? { kind: 'pageKey', pageKey: `${prefix}_list` } : { kind: 'external' };
  }
  // Any other absolute path or cross-designer reference (dashboard:, automation:, …)
  // leaves the DSL page set — not something this gate can verify.
  if (trimmed.startsWith('/') || trimmed.includes(':')) return { kind: 'external' };
  return { kind: 'pageKey', pageKey: trimmed };
}

/** The pageKey the default back link would land on for a form page. */
export function derivedListPageKey(pageKey) {
  const prefix = pageKey.endsWith('_form') ? pageKey.slice(0, -'_form'.length) : pageKey;
  return `${prefix}_list`;
}

/**
 * @param {Array<{file: string, page: any}>} entries every page in the scanned set
 * @param {Set<string>} allowed pageKeys exempted by the allowlist
 * @returns {Array<{pageKey: string, file: string, reason: string}>}
 */
export function findBrokenBackLinks(entries, allowed = new Set()) {
  const knownPageKeys = new Set(entries.map((e) => e.page.pageKey).filter(Boolean));
  const problems = [];
  for (const { file, page } of entries) {
    if (page?.kind !== 'form' || !page.pageKey) continue;
    if (allowed.has(page.pageKey)) continue;
    const declared = page?.extension?.backTo;
    if (declared != null && String(declared).trim()) {
      const target = classifyBackTo(declared);
      if (target.kind === 'pageKey' && !knownPageKeys.has(target.pageKey)) {
        problems.push({
          pageKey: page.pageKey,
          file,
          reason: `backTo "${declared}" resolves to pageKey "${target.pageKey}", which does not exist`,
        });
      }
      continue;
    }
    const fallback = derivedListPageKey(page.pageKey);
    if (!knownPageKeys.has(fallback)) {
      problems.push({
        pageKey: page.pageKey,
        file,
        reason: `no "${fallback}" page, so the default back link /p/${fallback.slice(0, -'_list'.length)} errors — declare extension.backTo (a pageKey, an absolute path, or "none")`,
      });
    }
  }
  return problems;
}

function loadAllowlist(scriptDir) {
  const file = path.join(scriptDir, ALLOWLIST_FILE);
  if (!fs.existsSync(file)) return new Set();
  const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
  const list = Array.isArray(parsed) ? parsed : parsed.allow || [];
  return new Set(list.map((item) => (typeof item === 'string' ? item : item.pageKey)));
}

function main(argv) {
  const roots = [];
  let json = false;
  let quiet = false;
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--root') {
      roots.push(path.resolve(argv[i + 1]));
      i += 1;
    } else if (argv[i] === '--json') json = true;
    else if (argv[i] === '--quiet') quiet = true;
  }
  if (roots.length === 0) {
    // Platform repo keeps plugins under plugins/; a plugin repo *is* the plugin root.
    const pluginDir = path.join(REPO_ROOT, 'plugins');
    roots.push(fs.existsSync(pluginDir) ? pluginDir : REPO_ROOT);
  }

  const entries = [];
  for (const root of roots) {
    for (const file of findPageFiles(root)) {
      let pages;
      try {
        pages = readPages(file);
      } catch (err) {
        console.error(`[check-form-back-link] cannot parse ${file}: ${err.message}`);
        return 2;
      }
      for (const page of pages) entries.push({ file, page });
    }
  }

  const problems = findBrokenBackLinks(entries, loadAllowlist(path.join(REPO_ROOT, 'scripts')));
  const formCount = entries.filter((e) => e.page?.kind === 'form').length;

  if (json) {
    console.log(JSON.stringify({ scanned: entries.length, forms: formCount, problems }, null, 2));
  } else if (problems.length > 0) {
    console.error(`[check-form-back-link] ${problems.length} form page(s) with an unreachable back link:\n`);
    for (const p of problems) {
      console.error(`  ${p.pageKey}`);
      console.error(`    ${p.reason}`);
      console.error(`    ${path.relative(REPO_ROOT, p.file)}\n`);
    }
  } else if (!quiet) {
    console.log(`[check-form-back-link] OK — ${formCount} form page(s), every back link reachable.`);
  }
  return problems.length > 0 ? 1 : 0;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  process.exit(main(process.argv.slice(2)));
}
