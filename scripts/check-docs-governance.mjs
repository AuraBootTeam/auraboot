#!/usr/bin/env node
// Documentation governance checker.
//
// Enforces the contract in docs/standards/meta/documentation-governance.md:
//   - frontmatter present + valid (type/status enums, required-by-type)
//   - naming conventions (HANDOVER-{date}-{slug}, DDR-{date}-{slug}, no static HANDOVER.md)
//   - location (declared type must match its directory)
//   - precipitation gate (status:closed ⇒ distilled_to resolves; canonical can't be distilled)
//   - link integrity (relates_to / superseded_by / supersedes / distilled_to resolve)
//   - archive hygiene (archived doc still status:active) + stale active process docs
//   - baseline grandfathering so ~4000 legacy docs don't fail Day-1
//
// Self-contained (node: builtins only) so it vendors per-repo and runs under
// CI single-repo checkout, exactly like scripts/page-golden-audit.mjs.
//
// Usage:
//   node scripts/check-docs-governance.mjs [--strict] [--git] [--audit-legacy]
//                                          [--json] [--quiet] [--changed <path>...]
// Exit: 0 = clean (warnings allowed), 1 = error (or warning under --strict),
//       2 = config/IO failure.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

// Contract version published in the canonical governance doc's frontmatter.
// Bump in lockstep with the schema/enums there; vendored copies that fall
// behind report S-DOCS-CHECKER-STALE.
// v3: recognize conventional public/product-documentation dirs (guides,
//     api-reference, …) as product-doc/system-reference homes (DIR_TYPE_RULES).
const SCHEMA_VERSION = 3;

const GOVERNANCE_DOC = 'docs/standards/meta/documentation-governance.md';

const STATUS_ENUM = new Set(['active', 'closed', 'shipped', 'superseded', 'abandoned', 'stale']);
const CANONICAL_TYPES = new Set([
  'standard-core', 'standard-meta', 'standard-e2e', 'ddr', 'agent-rule', 'system-reference', 'mockup-reference',
  'product-doc',
]);
const PROCESS_TYPES = new Set([
  'handover', 'retro', 'plan-design', 'plan-impl', 'backlog', 'run-log', 'artifact', 'worktree-scratch',
]);
const TYPE_ENUM = new Set([...CANONICAL_TYPES, ...PROCESS_TYPES]);

// directory (relative to docs/) -> allowed declared types. null = any (archive).
const DIR_TYPE_RULES = [
  { prefix: 'standards/core', types: ['standard-core'] },
  { prefix: 'standards/meta', types: ['standard-meta'] },
  { prefix: 'standards/e2e-extras', types: ['standard-e2e'] },
  { prefix: 'standards/decisions', types: ['ddr'] },
  { prefix: 'agent-rules', types: ['agent-rule'] },
  { prefix: 'system-reference', types: ['system-reference'] },
  { prefix: 'product-docs', types: ['product-doc'] },
  { prefix: 'mockups', types: ['mockup-reference'] },
  { prefix: 'handover', types: ['handover'] },
  { prefix: 'retro', types: ['retro'] },
  { prefix: 'plans', types: ['plan-design', 'plan-impl'] },
  { prefix: 'backlog', types: ['backlog', 'retro'] },
  { prefix: 'superpowers', types: ['run-log', 'plan-design', 'plan-impl', 'artifact'] },
  { prefix: 'decisions', types: ['ddr'] }, // lite profile
  // Conventional public / product-documentation dirs (open-source doc-site
  // layout). Permissive: a repo without these dirs is unaffected. Public docs
  // are canonical type {product-doc, system-reference} — type+status only, no
  // process/precipitation rules. (SCHEMA_VERSION 3)
  ...[
    'getting-started', 'guides', 'use-cases', 'api-reference', 'core-concepts',
    'architecture', 'connector-sdk', 'plugin-development', 'deployment',
    'operations', 'community', 'mobile', 'releases',
  ].map((prefix) => ({ prefix, types: ['product-doc', 'system-reference'] })),
  { prefix: 'archive', types: null },
];

// Basenames that are structural navigation, never governed content docs.
const EXEMPT_BASENAMES = new Set(['README.md', 'INDEX.md', 'AGENTS.md', 'CLAUDE.md', 'CHANGELOG.md']);

const HANDOVER_RE = /^HANDOVER-\d{4}-\d{2}-\d{2}-[a-z0-9][a-z0-9-]*\.md$/;
const DDR_RE = /^DDR-\d{4}-\d{2}-\d{2}-[a-z0-9][a-z0-9-]*\.md$/;
const DATED_PREFIX_RE = /^\d{4}-\d{2}-\d{2}-/;

// ---------------------------------------------------------------------------
// fs / parsing helpers (mirror page-golden-audit.mjs)
// ---------------------------------------------------------------------------

function rel(repoRoot, filePath) {
  return path.relative(repoRoot, filePath).replaceAll(path.sep, '/');
}

function collectMarkdown(targetPath) {
  const stats = fs.statSync(targetPath);
  if (stats.isFile()) return targetPath.endsWith('.md') ? [targetPath] : [];
  if (!stats.isDirectory()) return [];
  const files = [];
  for (const entry of fs.readdirSync(targetPath, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === '.git' || entry.name === 'build') continue;
    files.push(...collectMarkdown(path.join(targetPath, entry.name)));
  }
  return files;
}

// Minimal frontmatter parser: returns { present, data } for a leading --- block.
// Supports `key: scalar`, `key: [a, b]`, and indented `- item` lists. Enough
// for the 6-field governance schema; not a general YAML parser.
function parseFrontmatter(text) {
  if (!text.startsWith('---')) return { present: false, data: {} };
  const end = text.indexOf('\n---', 3);
  if (end === -1) return { present: false, data: {} };
  const block = text.slice(text.indexOf('\n') + 1, end + 1);
  const data = {};
  let currentListKey = null;
  for (const rawLine of block.split('\n')) {
    const line = rawLine.replace(/\s+$/, '');
    if (!line.trim()) continue;
    const listItem = line.match(/^\s+-\s+(.*)$/);
    if (listItem && currentListKey) {
      data[currentListKey].push(stripQuotes(listItem[1]));
      continue;
    }
    const kv = line.match(/^([A-Za-z_][\w-]*):\s*(.*)$/);
    if (!kv) { currentListKey = null; continue; }
    const [, key, rawVal] = kv;
    const val = rawVal.trim();
    if (val === '') {
      data[key] = [];
      currentListKey = key;
    } else if (val.startsWith('[') && val.endsWith(']')) {
      data[key] = val.slice(1, -1).split(',').map((s) => stripQuotes(s.trim())).filter(Boolean);
      currentListKey = null;
    } else {
      data[key] = stripQuotes(val);
      currentListKey = null;
    }
  }
  return { present: true, data };
}

function stripQuotes(s) {
  return s.replace(/^['"]|['"]$/g, '');
}

function asArray(v) {
  if (v == null) return [];
  return Array.isArray(v) ? v : [v];
}

function matchesGlob(relPath, glob) {
  // supports trailing /** and simple * within a segment
  const re = new RegExp(
    '^' + glob
      .replaceAll('.', '\\.')
      .replaceAll('/**', '@@GLOBSTAR@@')
      .replaceAll('*', '[^/]*')
      .replaceAll('@@GLOBSTAR@@', '(/.*)?') + '$',
  );
  return re.test(relPath);
}

// ---------------------------------------------------------------------------
// routing / classification
// ---------------------------------------------------------------------------

function dirRuleFor(relPath) {
  if (!relPath.startsWith('docs/')) return undefined;
  const underDocs = relPath.slice('docs/'.length);
  for (const rule of DIR_TYPE_RULES) {
    if (underDocs === rule.prefix || underDocs.startsWith(rule.prefix + '/')) return rule;
  }
  return undefined; // under docs/ but in an undeclared dir
}

function expectedAxisForType(type) {
  if (CANONICAL_TYPES.has(type)) return 'canonical';
  if (PROCESS_TYPES.has(type)) return 'process';
  return undefined;
}

// ---------------------------------------------------------------------------
// audit
// ---------------------------------------------------------------------------

function loadConfig(repoRoot) {
  const cfgPath = path.join(repoRoot, '.docs-governance.json');
  if (!fs.existsSync(cfgPath)) {
    return { error: `.docs-governance.json not found at ${rel(repoRoot, cfgPath)}` };
  }
  let cfg;
  try {
    cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
  } catch (e) {
    return { error: `.docs-governance.json is not valid JSON: ${e.message}` };
  }
  cfg.profile = cfg.profile || 'lite';
  cfg.baseline_allowlist = cfg.baseline_allowlist || [];
  cfg.archive_retention_days = cfg.archive_retention_days ?? 60;
  cfg.allowed_root_md = new Set(cfg.allowed_root_md || ['README.md', 'AGENTS.md', 'CLAUDE.md', 'CHANGELOG.md']);
  return { cfg };
}

function gitFilesAddedSince(repoRoot, anchor) {
  try {
    const out = execFileSync(
      'git',
      ['-C', repoRoot, 'log', `--since=${anchor}`, '--diff-filter=A', '--name-only', '--pretty=format:'],
      { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 },
    );
    return new Set(out.split('\n').map((s) => s.trim()).filter(Boolean));
  } catch {
    return null; // not a git repo / git missing -> caller treats as "unknown"
  }
}

function daysBetween(fromYmd, toDate) {
  const from = new Date(`${fromYmd}T00:00:00Z`);
  if (Number.isNaN(from.getTime())) return null;
  return Math.floor((toDate.getTime() - from.getTime()) / 86400000);
}

// Build the audit. `now` is injected for deterministic tests.
export function auditRepo(repoRoot, options = {}) {
  const findings = [];
  const checked = [];
  const add = (severity, code, filePath, message, location = '') => {
    findings.push({ severity, code, file: rel(repoRoot, filePath), location, message });
  };
  // baseline-gated finding: error normally, warning when grandfathered (unless --audit-legacy).
  const addGated = (grandfathered, code, filePath, message, location = '') => {
    const severity = grandfathered && !options.auditLegacy ? 'warning' : 'error';
    add(severity, code, filePath, message, location);
  };

  const { cfg, error } = loadConfig(repoRoot);
  if (error) return { configError: error, checked, findings };

  const now = options.now || new Date();
  const anchor = cfg.baseline_anchor;
  if (!anchor) {
    add('error', 'S-DOCS-BASELINE-MISSING', path.join(repoRoot, '.docs-governance.json'),
      'config has no baseline_anchor; grandfathering would be silently disabled.');
    return { checked, findings };
  }

  // checker-staleness vs the canonical governance doc (only where it exists).
  const govPath = path.join(repoRoot, GOVERNANCE_DOC);
  if (fs.existsSync(govPath)) {
    const { data } = parseFrontmatter(fs.readFileSync(govPath, 'utf8'));
    const docVersion = Number(data.schema_version);
    if (Number.isFinite(docVersion) && docVersion !== SCHEMA_VERSION) {
      add('warning', 'S-DOCS-CHECKER-STALE', govPath,
        `checker SCHEMA_VERSION=${SCHEMA_VERSION} but governance doc schema_version=${docVersion}; re-vendor the checker.`);
    }
  }

  const addedSince = options.addedSince || (options.git ? gitFilesAddedSince(repoRoot, anchor) : null);

  // collect targets
  const roots = options.changed && options.changed.length
    ? options.changed.map((p) => path.resolve(repoRoot, p))
    : [path.join(repoRoot, 'docs')];
  const mdFiles = [];
  for (const r of roots) {
    if (!fs.existsSync(r)) { add('error', 'S-DOCS-TARGET-MISSING', r, 'audit target does not exist.'); continue; }
    mdFiles.push(...collectMarkdown(r));
  }

  // stray root .md (only when auditing the whole repo, not a --changed subset)
  if (!options.changed || !options.changed.length) {
    for (const entry of fs.readdirSync(repoRoot, { withFileTypes: true })) {
      if (!entry.isFile() || !entry.name.endsWith('.md')) continue;
      if (cfg.allowed_root_md.has(entry.name)) continue;
      const abs = path.join(repoRoot, entry.name);
      const gf = isGrandfathered(rel(repoRoot, abs), undefined, cfg, addedSince);
      addGated(gf, 'S-DOCS-STRAY-ROOT', abs,
        `stray .md at repo root; move it under docs/ or add to allowed_root_md. (${entry.name})`);
    }
  }

  for (const abs of mdFiles) {
    const relPath = rel(repoRoot, abs);
    const base = path.basename(abs);
    if (EXEMPT_BASENAMES.has(base)) continue; // structural nav/meta files
    checked.push(relPath);

    const text = fs.readFileSync(abs, 'utf8');
    const { present, data } = parseFrontmatter(text);
    const hasNoPrecip = /<!--\s*no-precipitation:/i.test(text);
    const grandfathered = isGrandfathered(relPath, data.type, cfg, addedSince);
    const rule = dirRuleFor(relPath);

    // --- C: ungoverned directory ---
    if (relPath.startsWith('docs/') && rule === undefined) {
      add('warning', 'S-DOCS-UNGOVERNED-DIR', abs,
        'doc lives in a docs/ subdir not declared in the routing table; declare it or relocate.');
    }

    // --- B: naming (independent of frontmatter) ---
    auditNaming(relPath, base, rule, grandfathered, addGated, abs);

    // --- A: frontmatter presence / validity ---
    if (!present) {
      addGated(grandfathered, 'S-DOCS-FM-MISSING', abs, 'missing frontmatter (type + status required).');
      continue; // nothing else to validate without frontmatter
    }

    const type = data.type;
    const status = data.status;
    if (!type || !status) {
      addGated(grandfathered, 'S-DOCS-FM-REQUIRED', abs, 'frontmatter must include both type and status.');
    }
    if (type && !TYPE_ENUM.has(type)) {
      addGated(grandfathered, 'S-DOCS-FM-ENUM', abs, `unknown type "${type}".`);
    }
    if (status && !STATUS_ENUM.has(status)) {
      addGated(grandfathered, 'S-DOCS-FM-ENUM', abs, `unknown status "${status}".`);
    }
    const axis = expectedAxisForType(type);
    if (axis === 'process' && !data.created) {
      addGated(grandfathered, 'S-DOCS-FM-REQUIRED', abs, 'process docs must declare created: YYYY-MM-DD.');
    }
    if (data.created && !/^\d{4}-\d{2}-\d{2}$/.test(data.created)) {
      add('warning', 'S-DOCS-FM-DATEFMT', abs, `created should be YYYY-MM-DD, got "${data.created}".`);
    }

    // --- C: location (declared type must match directory) ---
    if (type && rule && rule.types && !rule.types.includes(type)) {
      addGated(grandfathered, 'S-DOCS-LOC-DISALLOWED', abs,
        `type "${type}" not allowed under docs/${rule.prefix}/ (expected ${rule.types.join('|')}).`);
    }

    // --- precipitation gate + supersession ---
    auditClosure(repoRoot, relPath, abs, data, axis, hasNoPrecip, grandfathered, add, addGated, options);

    // --- E: link integrity ---
    auditLinks(repoRoot, abs, data, options, add);

    // --- F: archive hygiene ---
    if (relPath.startsWith('docs/archive/') && status === 'active') {
      add('warning', 'S-DOCS-ARCHIVE-STATUS', abs, 'doc is under archive/ but still status:active.');
    }

    // --- D: stale active process doc (no git needed) ---
    if (axis === 'process' && status === 'active' && data.created && !relPath.startsWith('docs/archive/')) {
      const age = daysBetween(data.created, now);
      if (age != null && age > cfg.archive_retention_days && asArray(data.distilled_to).length === 0) {
        add('warning', 'S-DOCS-ORPHAN-STALE', abs,
          `active process doc is ${age}d old (> ${cfg.archive_retention_days}d) with no distilled_to; precipitate + archive or mark BLOCKED_WITH_OWNER.`);
      }
    }
  }

  return { checked: [...new Set(checked)], findings, profile: cfg.profile };
}

function isGrandfathered(relPath, declaredType, cfg, addedSince) {
  if (cfg.baseline_allowlist.some((g) => matchesGlob(relPath, g))) return true;
  // Declaring a governed type: opts the doc into enforcement (even pre-commit,
  // so authors get errors locally). 1082 legacy docs carry foreign frontmatter
  // (no governed type:), so presence-of-frontmatter alone must NOT enforce.
  if (declaredType && TYPE_ENUM.has(declaredType)) return false;
  // Otherwise enforce only if git proves the file was added on/after the anchor.
  if (addedSince && addedSince.has(relPath)) return false;
  return true;
}

function auditNaming(relPath, base, rule, grandfathered, addGated, abs) {
  const underDocs = relPath.startsWith('docs/') ? relPath.slice('docs/'.length) : '';
  const inHandover = rule?.prefix === 'handover' || base.startsWith('HANDOVER');
  if (inHandover) {
    if (base === 'HANDOVER.md') {
      addGated(grandfathered, 'S-DOCS-NAME-STATIC-HANDOVER', abs,
        'static HANDOVER.md is banned (parallel sessions overwrite); use HANDOVER-{YYYY-MM-DD}-{slug}.md.');
    } else if (!HANDOVER_RE.test(base)) {
      addGated(grandfathered, 'S-DOCS-NAME-HANDOVER', abs,
        `handover must match HANDOVER-{YYYY-MM-DD}-{slug}.md, got ${base}.`);
    }
  }
  if (rule?.prefix === 'standards/decisions' || rule?.prefix === 'decisions') {
    if (!DDR_RE.test(base)) {
      addGated(grandfathered, 'S-DOCS-NAME-DDR', abs, `DDR must match DDR-{YYYY-MM-DD}-{slug}.md, got ${base}.`);
    }
  }
  // dated-prefix warning for retro / plans / backlog dated items (warning only)
  if ((rule?.prefix === 'retro' || base.endsWith('-retro.md')) && !DATED_PREFIX_RE.test(base)) {
    addGated(true, 'S-DOCS-NAME-DATED', abs, `retro should be {YYYY-MM-DD}-{slug}-retro.md, got ${base}.`);
  }
}

function auditClosure(repoRoot, relPath, abs, data, axis, hasNoPrecip, grandfathered, add, addGated, options) {
  const distilled = asArray(data.distilled_to);
  // rule 5: distilled_to only on process docs
  if (distilled.length && axis === 'canonical') {
    addGated(grandfathered, 'S-DOCS-DISTILL-ON-CANONICAL', abs,
      'canonical docs cannot declare distilled_to (they are the precipitation target).');
  }
  // rule 3: status:closed ⇒ distilled_to present (unless no-precipitation note) and resolves
  if (data.status === 'closed' && axis === 'process') {
    if (distilled.length === 0 && !hasNoPrecip) {
      addGated(grandfathered, 'S-DOCS-CLOSED-NO-DISTILL', abs,
        'status:closed requires distilled_to (where the durable lesson landed) or a <!-- no-precipitation: ... --> note.');
    }
    for (const target of distilled) {
      const resolved = resolveDocPath(repoRoot, abs, target);
      if (resolved) {
        if (options.deep) {
          // rule 6: target must be canonical-typed (only when --deep, reads target frontmatter)
          const tf = parseFrontmatter(fs.readFileSync(resolved, 'utf8'));
          if (tf.present && tf.data.type && !CANONICAL_TYPES.has(tf.data.type)) {
            addGated(grandfathered, 'S-DOCS-DISTILL-TARGET-NONCANON', abs,
              `distilled_to target ${target} is type "${tf.data.type}" (must be canonical).`);
          }
        }
      } else if (!isAcceptedCrossRepoTarget(target)) {
        addGated(grandfathered, 'S-DOCS-DISTILL-UNRESOLVED', abs, `distilled_to target not found: ${target}`);
      }
    }
  }
  // rule 4: superseded ⇒ superseded_by resolves
  if (data.status === 'superseded') {
    if (!data.superseded_by) {
      addGated(grandfathered, 'S-DOCS-SUPERSEDE-NO-TARGET', abs, 'status:superseded requires superseded_by.');
    } else if (!resolveDocPath(repoRoot, abs, data.superseded_by) && !isAcceptedCrossRepoTarget(data.superseded_by)) {
      add('warning', 'S-DOCS-LINK-RELATES', abs, `superseded_by target not found: ${data.superseded_by}`);
    }
  }
}

function auditLinks(repoRoot, abs, data, options, add) {
  const severity = options.strict ? 'error' : 'warning';
  for (const key of ['relates_to', 'supersedes', 'superseded_by', 'distilled_to']) {
    for (const target of asArray(data[key])) {
      if (!resolveDocPath(repoRoot, abs, target) && !isAcceptedCrossRepoTarget(target)) {
        add(severity, 'S-DOCS-LINK-RELATES', abs, `${key} target not found: ${target}`);
      }
    }
  }
}

function resolveDocPath(repoRoot, fromFile, target) {
  // Strip a `#anchor` and an optional trailing ` (human description)` annotation —
  // distilled_to entries are commonly annotated, e.g.
  //   path/to/canonical.md (what landed there)
  const cleaned = String(target).split('#')[0].replace(/\s+\([^)]*\)\s*$/, '').trim();
  if (!cleaned) return null;
  const candidates = [
    path.resolve(repoRoot, cleaned),
    path.resolve(path.dirname(fromFile), cleaned),
    // Cross-repo precipitation: a doc may distill a lesson into the sibling
    // canonical repo (OSS → auraboot-enterprise/...), which lives next to this
    // repo in the workspace. Resolve such targets against the workspace parent so
    // a side-by-side `auraboot-enterprise/docs/...` reference is honored instead of
    // erroring as "target not found".
    path.resolve(repoRoot, '..', cleaned),
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  return null;
}

// Sibling canonical repo(s) an OSS process doc may legitimately precipitate a lesson into
// (OSS → auraboot-enterprise canonical). When this gate runs in CI with only this repo checked
// out, such a target cannot be resolved (the sibling repo isn't present) — it is a recognized
// cross-repo precipitation reference, not a dead link, so accept it. When the sibling IS checked
// out side-by-side, resolveDocPath's `..` candidate validates the path normally.
const CROSS_REPO_PREFIXES = ['auraboot-enterprise/'];
function isAcceptedCrossRepoTarget(target) {
  if (!target) return false;
  const cleaned = String(target).split('#')[0].replace(/\s+\([^)]*\)\s*$/, '').trim();
  return CROSS_REPO_PREFIXES.some((p) => cleaned.startsWith(p));
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const options = { strict: false, git: false, auditLegacy: false, json: false, quiet: false, deep: false, changed: [] };
  let collectingChanged = false;
  for (const arg of argv) {
    if (collectingChanged && !arg.startsWith('--')) { options.changed.push(arg); continue; }
    collectingChanged = false;
    switch (arg) {
      case '--strict': options.strict = true; break;
      case '--git': options.git = true; break;
      case '--audit-legacy': options.auditLegacy = true; break;
      case '--json': options.json = true; break;
      case '--quiet': options.quiet = true; break;
      case '--deep': options.deep = true; break;
      case '--changed': collectingChanged = true; break;
      case '--help': case '-h': options.help = true; break;
      default: break;
    }
  }
  return options;
}

function printUsage() {
  console.log(`Usage:
  node scripts/check-docs-governance.mjs [options]

Options:
  --strict        treat warnings as failures (CI).
  --git           use git to detect post-baseline files (enforce frontmatter on new docs).
  --audit-legacy  report full debt at true severity but always exit 0 (planning).
  --deep          read distilled_to targets to verify they are canonical-typed.
  --changed <p>   scope to specific paths (fast pre-push), space-separated.
  --json          machine-readable output.
  --quiet         summary only.

Reads .docs-governance.json from the repo root. See
docs/standards/meta/documentation-governance.md for the contract.`);
}

function printResult(result, options) {
  if (result.configError) {
    console.error(`CONFIG ERROR: ${result.configError}`);
    return;
  }
  const errors = result.findings.filter((f) => f.severity === 'error');
  const warnings = result.findings.filter((f) => f.severity === 'warning');
  if (options.json) {
    console.log(JSON.stringify({ profile: result.profile, checked: result.checked.length, errors, warnings }, null, 2));
    return;
  }
  console.log('=== Docs Governance Check ===');
  console.log(`profile=${result.profile ?? '?'}  checked=${result.checked.length} doc(s)`);
  if (!options.quiet) {
    for (const [label, items] of [['ERRORS', errors], ['WARNINGS', warnings]]) {
      if (!items.length) continue;
      console.log(`\n${label}:`);
      for (const f of items) {
        const loc = f.location ? `:${f.location}` : '';
        console.log(`  ${f.severity.toUpperCase()} ${f.code} ${f.file}${loc} -- ${f.message}`);
      }
    }
  }
  const suppressed = options.auditLegacy ? ' (audit-legacy: reporting full debt, exit 0)' : '';
  console.log(`\nSummary: ${errors.length} error(s), ${warnings.length} warning(s)${suppressed}`);
  console.log(errors.length || (options.strict && warnings.length) ? (options.auditLegacy ? 'AUDIT (non-blocking).' : 'FAILED.') : 'PASSED.');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) { printUsage(); process.exit(0); }
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const result = auditRepo(repoRoot, options);
  printResult(result, options);
  if (result.configError) process.exit(2);
  const hasErrors = result.findings.some((f) => f.severity === 'error');
  const hasWarnings = result.findings.some((f) => f.severity === 'warning');
  if (options.auditLegacy) process.exit(0);
  process.exit(hasErrors || (options.strict && hasWarnings) ? 1 : 0);
}
