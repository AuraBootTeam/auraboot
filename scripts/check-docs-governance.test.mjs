import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { auditRepo } from './check-docs-governance.mjs';

// --- fixture helpers ---------------------------------------------------------

function makeRepo(config = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'docsgov-'));
  const cfg = {
    profile: 'full',
    baseline_anchor: '2026-06-04',
    baseline_allowlist: ['docs/archive/**', 'docs/system-reference/**'],
    archive_retention_days: 60,
    allowed_root_md: ['README.md', 'AGENTS.md'],
    ...config,
  };
  fs.writeFileSync(path.join(root, '.docs-governance.json'), JSON.stringify(cfg));
  return root;
}

function write(root, relPath, content) {
  const abs = path.join(root, relPath);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, content);
  return abs;
}

function fm(fields, body = '\n# Title\n') {
  const lines = Object.entries(fields).map(([k, v]) =>
    Array.isArray(v) ? `${k}: [${v.join(', ')}]` : `${k}: ${v}`);
  return `---\n${lines.join('\n')}\n---\n${body}`;
}

function codes(result) {
  return result.findings.map((f) => f.code);
}
function bySeverity(result, sev) {
  return result.findings.filter((f) => f.severity === sev).map((f) => f.code);
}

// --- tests -------------------------------------------------------------------

test('missing config -> configError, exit-2 signal', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'docsgov-noconf-'));
  const result = auditRepo(root);
  assert.ok(result.configError, 'should report a config error');
});

test('missing baseline_anchor -> S-DOCS-BASELINE-MISSING error', () => {
  const root = makeRepo({ baseline_anchor: undefined });
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-BASELINE-MISSING'));
});

test('clean canonical doc -> no error', () => {
  const root = makeRepo();
  write(root, 'docs/standards/meta/foo.md', fm({ type: 'standard-meta', status: 'active' }));
  const result = auditRepo(root);
  assert.deepEqual(bySeverity(result, 'error'), []);
});

test('clean product-doc under docs/product-docs/ -> no error', () => {
  const root = makeRepo();
  write(root, 'docs/product-docs/getting-started/01-quickstart.md', fm({ type: 'product-doc', status: 'active' }));
  const result = auditRepo(root);
  assert.deepEqual(bySeverity(result, 'error'), []);
});

test('wrong type under docs/product-docs/ -> S-DOCS-LOC-DISALLOWED', () => {
  const root = makeRepo();
  write(root, 'docs/product-docs/x.md', fm({ type: 'system-reference', status: 'active' }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-LOC-DISALLOWED'));
});

test('public/product doc dirs (guides, api-reference, architecture) are governed homes, no UNGOVERNED-DIR (v3)', () => {
  const root = makeRepo();
  write(root, 'docs/guides/01-intro.md', fm({ type: 'product-doc', status: 'active' }));
  write(root, 'docs/api-reference/rest.md', fm({ type: 'product-doc', status: 'active' }));
  write(root, 'docs/architecture/overview.md', fm({ type: 'system-reference', status: 'active' }));
  write(root, 'docs/core-concepts/model.md', fm({ type: 'system-reference', status: 'active' }));
  const result = auditRepo(root);
  assert.deepEqual(bySeverity(result, 'error'), []);
  assert.ok(!codes(result).includes('S-DOCS-UNGOVERNED-DIR'), 'public dirs must be routed, not ungoverned');
});

test('clean process handover -> no error', () => {
  const root = makeRepo();
  write(root, 'docs/handover/HANDOVER-2026-06-04-foo-bar.md',
    fm({ type: 'handover', status: 'active', created: '2026-06-04' }));
  const result = auditRepo(root);
  assert.deepEqual(bySeverity(result, 'error'), []);
});

test('status:closed without distilled_to -> S-DOCS-CLOSED-NO-DISTILL', () => {
  const root = makeRepo();
  write(root, 'docs/handover/HANDOVER-2026-06-04-foo.md',
    fm({ type: 'handover', status: 'closed', created: '2026-06-04' }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-CLOSED-NO-DISTILL'));
});

test('status:closed with no-precipitation note -> allowed', () => {
  const root = makeRepo();
  write(root, 'docs/handover/HANDOVER-2026-06-04-foo.md',
    fm({ type: 'handover', status: 'closed', created: '2026-06-04' },
      '\n<!-- no-precipitation: trivial fix, nothing reusable -->\n# T\n'));
  const result = auditRepo(root);
  assert.ok(!codes(result).includes('S-DOCS-CLOSED-NO-DISTILL'));
});

test('status:closed with resolving distilled_to -> allowed', () => {
  const root = makeRepo();
  write(root, 'docs/standards/core/lesson.md', fm({ type: 'standard-core', status: 'active' }));
  write(root, 'docs/handover/HANDOVER-2026-06-04-foo.md',
    fm({ type: 'handover', status: 'closed', created: '2026-06-04', distilled_to: ['docs/standards/core/lesson.md'] }));
  const result = auditRepo(root);
  assert.ok(!codes(result).includes('S-DOCS-CLOSED-NO-DISTILL'));
  assert.ok(!codes(result).includes('S-DOCS-DISTILL-UNRESOLVED'));
});

test('status:closed with broken distilled_to -> S-DOCS-DISTILL-UNRESOLVED', () => {
  const root = makeRepo();
  write(root, 'docs/handover/HANDOVER-2026-06-04-foo.md',
    fm({ type: 'handover', status: 'closed', created: '2026-06-04', distilled_to: ['docs/standards/core/ghost.md'] }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-DISTILL-UNRESOLVED'));
});

test('status:closed with cross-repo (auraboot-enterprise/) distilled_to -> accepted (sibling repo not checked out in CI)', () => {
  const root = makeRepo();
  write(root, 'docs/retro/2026-06-10-bar.md',
    fm({ type: 'retro', status: 'closed', created: '2026-06-10',
      distilled_to: ['auraboot-enterprise/docs/agent-rules/engineering-gotchas/backend-spring-db.md (the lesson)'] }));
  const result = auditRepo(root);
  // The sibling canonical repo can't be resolved from this repo's CI checkout, but a recognized
  // cross-repo precipitation target is a valid reference, not a dead link.
  assert.ok(!codes(result).includes('S-DOCS-DISTILL-UNRESOLVED'));
  assert.ok(!codes(result).includes('S-DOCS-CLOSED-NO-DISTILL'));
  assert.ok(!codes(result).includes('S-DOCS-LINK-RELATES'));
});

test('distilled_to on canonical doc -> S-DOCS-DISTILL-ON-CANONICAL', () => {
  const root = makeRepo();
  write(root, 'docs/standards/core/x.md',
    fm({ type: 'standard-core', status: 'active', distilled_to: ['docs/standards/meta/y.md'] }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-DISTILL-ON-CANONICAL'));
});

test('static HANDOVER.md -> S-DOCS-NAME-STATIC-HANDOVER', () => {
  const root = makeRepo();
  write(root, 'docs/handover/HANDOVER.md', fm({ type: 'handover', status: 'active', created: '2026-06-04' }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-NAME-STATIC-HANDOVER'));
});

test('mis-named handover -> S-DOCS-NAME-HANDOVER', () => {
  const root = makeRepo();
  write(root, 'docs/handover/handover-foo.md', fm({ type: 'handover', status: 'active', created: '2026-06-04' }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-NAME-HANDOVER'));
});

test('mis-named DDR -> S-DOCS-NAME-DDR', () => {
  const root = makeRepo();
  write(root, 'docs/standards/decisions/2026-06-04-foo.md', fm({ type: 'ddr', status: 'active' }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-NAME-DDR'));
});

test('type mismatch with directory -> S-DOCS-LOC-DISALLOWED', () => {
  const root = makeRepo();
  write(root, 'docs/standards/core/x.md', fm({ type: 'agent-rule', status: 'active' }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-LOC-DISALLOWED'));
});

test('declaring a governed type opts into enforcement (error) even without git', () => {
  // A doc that declares type: standard-core but is mis-located is enforced as an
  // ERROR with no --git and no addedSince, because it opted into the scheme.
  const root = makeRepo();
  write(root, 'docs/agent-rules/x.md', fm({ type: 'standard-core', status: 'active' }));
  const result = auditRepo(root);
  assert.ok(bySeverity(result, 'error').includes('S-DOCS-LOC-DISALLOWED'));
});

test('legacy foreign frontmatter (no governed type) is grandfathered, not enforced', () => {
  // The 1082-legacy-doc case: has frontmatter but no governed type: -> warning only.
  const root = makeRepo();
  write(root, 'docs/analysis/old-report.md',
    '---\ntitle: Old Report\ndate: 2026-03-01\nstatus: final\n---\n# body\n');
  const result = auditRepo(root); // no --git
  assert.deepEqual(bySeverity(result, 'error'), []);
});

test('unknown type/status -> S-DOCS-FM-ENUM', () => {
  const root = makeRepo();
  write(root, 'docs/standards/meta/x.md', fm({ type: 'bogus-type', status: 'weird' }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-FM-ENUM'));
});

test('legacy doc (no frontmatter, no git) is grandfathered -> warning not error', () => {
  const root = makeRepo();
  write(root, 'docs/backlog/old-notes.md', '# legacy backlog, no frontmatter\n');
  const result = auditRepo(root); // no --git
  assert.ok(bySeverity(result, 'warning').includes('S-DOCS-FM-MISSING'));
  assert.ok(!bySeverity(result, 'error').includes('S-DOCS-FM-MISSING'));
});

test('post-baseline doc (git-added since anchor) missing frontmatter -> error', () => {
  const root = makeRepo();
  write(root, 'docs/backlog/new-notes.md', '# new backlog, no frontmatter\n');
  const result = auditRepo(root, { addedSince: new Set(['docs/backlog/new-notes.md']) });
  assert.ok(bySeverity(result, 'error').includes('S-DOCS-FM-MISSING'));
});

test('allowlisted path (system-reference) missing frontmatter -> grandfathered even if git-added', () => {
  const root = makeRepo();
  write(root, 'docs/system-reference/05-foo.md', '# legacy sysref\n');
  const result = auditRepo(root, { addedSince: new Set(['docs/system-reference/05-foo.md']) });
  assert.ok(!bySeverity(result, 'error').includes('S-DOCS-FM-MISSING'));
});

test('audit-legacy reports grandfathered findings at error severity', () => {
  const root = makeRepo();
  write(root, 'docs/backlog/old.md', '# legacy\n');
  const result = auditRepo(root, { auditLegacy: true });
  assert.ok(bySeverity(result, 'error').includes('S-DOCS-FM-MISSING'));
});

test('stale active process doc -> S-DOCS-ORPHAN-STALE', () => {
  const root = makeRepo();
  write(root, 'docs/plans/2026-01/2026-01-01-foo-design.md',
    fm({ type: 'plan-design', status: 'active', created: '2026-01-01' }));
  const result = auditRepo(root, { now: new Date('2026-06-04T00:00:00Z') });
  assert.ok(codes(result).includes('S-DOCS-ORPHAN-STALE'));
});

test('archive doc still active -> S-DOCS-ARCHIVE-STATUS', () => {
  const root = makeRepo();
  write(root, 'docs/archive/handover/2026-05/HANDOVER-2026-05-01-x.md',
    fm({ type: 'handover', status: 'active', created: '2026-05-01' }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-ARCHIVE-STATUS'));
});

test('ungoverned docs subdir -> S-DOCS-UNGOVERNED-DIR warning', () => {
  const root = makeRepo();
  write(root, 'docs/random-stuff/notes.md', fm({ type: 'backlog', status: 'active', created: '2026-06-04' }));
  const result = auditRepo(root);
  assert.ok(bySeverity(result, 'warning').includes('S-DOCS-UNGOVERNED-DIR'));
});

test('stray root .md -> S-DOCS-STRAY-ROOT', () => {
  const root = makeRepo();
  write(root, 'RANDOM_NOTES.md', '# stray\n');
  const result = auditRepo(root, { addedSince: new Set(['RANDOM_NOTES.md']) });
  assert.ok(codes(result).includes('S-DOCS-STRAY-ROOT'));
});

test('README/INDEX are exempt from frontmatter', () => {
  const root = makeRepo();
  write(root, 'docs/README.md', '# index, no frontmatter\n');
  write(root, 'docs/standards/INDEX.md', '# index\n');
  const result = auditRepo(root, { addedSince: new Set(['docs/README.md', 'docs/standards/INDEX.md']) });
  assert.ok(!codes(result).includes('S-DOCS-FM-MISSING'));
});

test('checker-stale: governance doc schema_version mismatch -> warning', () => {
  const root = makeRepo();
  write(root, 'docs/standards/meta/documentation-governance.md',
    fm({ type: 'standard-meta', status: 'active', schema_version: 999 }));
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DOCS-CHECKER-STALE'));
});

test('baseline_allowlist /** matches NESTED subdirs, not only direct children', () => {
  const root = makeRepo({ baseline_allowlist: ['docs/archive/**'] });
  write(root, 'docs/archive/backlog/2026-05/legacy.md', '# legacy ledger, no frontmatter\n');
  const result = auditRepo(root, { addedSince: new Set(['docs/archive/backlog/2026-05/legacy.md']) });
  assert.ok(!bySeverity(result, 'error').includes('S-DOCS-FM-MISSING'),
    'nested doc under a /** allowlist must be grandfathered, not error');
});
