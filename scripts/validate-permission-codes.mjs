#!/usr/bin/env node
/**
 * Cross-source permission-code validator.
 *
 * Catches the bug class where a permission code is referenced (Controller
 * @RequirePermission via Java string constant, frontend resources.ts, or
 * backend menus.json permissionCode) but never registered in the bootstrap
 * + plugin permissions.json union — so every role lookup returns false and
 * the user sees "no permission".
 *
 * Sources of truth (registered codes):
 *   - auraboot/platform/src/main/resources/tenant-templates/default-bootstrap.json
 *     -> permissions[].code
 *   - auraboot/plugins/*\/config/permissions.json (array of {code})
 *   - auraboot-enterprise/plugins/*\/config/permissions.json (skipped in --oss-only)
 *
 * References scanned:
 *   - auraboot/web-admin/app/plugins/*\/resources.ts
 *     -> object literal field `permission: '<code>'`.
 *   - auraboot/platform/src/main/java/com/auraboot/framework/permission/constants/*.java
 *     -> string literal values of `public static final String X = "...";`.
 *   - {oss[,enterprise]}/plugins/*\/config/menus.json `permissionCode`.
 *
 * Wildcard `*` is allowed (role binding wildcards).
 *
 * Modes:
 *   node scripts/validate-permission-codes.mjs
 *     Strict mode — exits 1 on ANY drift. Useful once baseline is cleared.
 *
 *   node scripts/validate-permission-codes.mjs --baseline=<path>
 *     Baseline mode — exits 1 only on drift NOT in the baseline file.
 *     This is what CI runs while the 175-hit historical drift is being
 *     reconciled (see docs/standards/meta/permission-code-naming.md).
 *
 *   node scripts/validate-permission-codes.mjs --write-baseline=<path>
 *     Snapshot current drift into <path>; always exits 0.
 *
 *   --oss-only
 *     Skip auraboot-enterprise/ checkout (used by OSS CI which only has
 *     the OSS repo).
 *
 *   --json
 *     Machine-readable JSON output (works with --baseline too).
 *
 * Baseline matching is by `{relFile, code, kind}` — line numbers are
 * ignored so unrelated edits don't churn the baseline.
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)

const argv = process.argv.slice(2)
const flag = (name) => argv.includes(`--${name}`)
const optValue = (name) => {
  const prefix = `--${name}=`
  const hit = argv.find((a) => a.startsWith(prefix))
  return hit ? hit.slice(prefix.length) : null
}

const OSS_ONLY = flag('oss-only')
const JSON_OUT = flag('json')
const BASELINE_PATH = optValue('baseline')
const WRITE_BASELINE_PATH = optValue('write-baseline')

function findRepoRoot(start, { ossOnly }) {
  let cur = path.dirname(start)
  for (let i = 0; i < 12; i++) {
    const hasOss = fs.existsSync(path.join(cur, 'auraboot'))
    const hasEnt = fs.existsSync(path.join(cur, 'auraboot-enterprise'))
    if (hasOss && (ossOnly || hasEnt)) return cur
    const parent = path.dirname(cur)
    if (parent === cur) break
    cur = parent
  }
  throw new Error(
    ossOnly
      ? 'Could not locate repo root containing auraboot/ sibling'
      : 'Could not locate repo root containing auraboot + auraboot-enterprise',
  )
}

const REPO_ROOT = findRepoRoot(__filename, { ossOnly: OSS_ONLY })
const OSS = path.join(REPO_ROOT, 'auraboot')
const ENT = path.join(REPO_ROOT, 'auraboot-enterprise')

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function walk(dir, predicate, hits = []) {
  if (!fs.existsSync(dir)) return hits
  for (const name of fs.readdirSync(dir)) {
    if (
      name === 'node_modules' || name === 'build' || name === 'dist' ||
      name === 'target' || name === '.git' || name === '.worktrees'
    ) continue
    const full = path.join(dir, name)
    const stat = fs.statSync(full)
    if (stat.isDirectory()) walk(full, predicate, hits)
    else if (predicate(full)) hits.push(full)
  }
  return hits
}

function collectRegisteredCodes() {
  const codes = new Set()
  const sources = []

  const bootstrapFile = path.join(OSS, 'platform/src/main/resources/tenant-templates/default-bootstrap.json')
  if (fs.existsSync(bootstrapFile)) {
    const data = readJson(bootstrapFile)
    for (const p of data.permissions ?? []) {
      if (p.code) codes.add(p.code)
    }
    sources.push(bootstrapFile)
  }

  const roots = [path.join(OSS, 'plugins')]
  if (!OSS_ONLY) roots.push(path.join(ENT, 'plugins'))
  for (const root of roots) {
    const files = walk(root, (f) => f.endsWith('/config/permissions.json'))
    for (const f of files) {
      const data = readJson(f)
      const arr = Array.isArray(data) ? data : data.permissions ?? []
      for (const p of arr) if (p.code) codes.add(p.code)
      sources.push(f)
    }
  }

  return { codes, sources }
}

function collectFrontendReferences() {
  const refs = []
  const root = path.join(OSS, 'web-admin/app/plugins')
  if (!fs.existsSync(root)) return refs
  const files = walk(root, (f) => f.endsWith('/resources.ts'))
  for (const f of files) {
    const text = fs.readFileSync(f, 'utf8')
    const lines = text.split('\n')
    lines.forEach((line, i) => {
      const m = line.match(/permission:\s*'([^']+)'/)
      if (m) refs.push({ file: f, line: i + 1, code: m[1], kind: 'frontend.resources.permission' })
    })
  }
  return refs
}

function collectJavaConstantValues() {
  const refs = []
  const dir = path.join(OSS, 'platform/src/main/java/com/auraboot/framework/permission/constants')
  if (!fs.existsSync(dir)) return refs
  const files = walk(dir, (f) => f.endsWith('.java'))
  for (const f of files) {
    const text = fs.readFileSync(f, 'utf8')
    const lines = text.split('\n')
    lines.forEach((line, i) => {
      const m = line.match(/public\s+static\s+final\s+String\s+\w+\s*=\s*"([^"]+)"\s*;/)
      if (m) refs.push({ file: f, line: i + 1, code: m[1], kind: 'java.permission.constant' })
    })
  }
  return refs
}

function collectMenuReferences() {
  const refs = []
  const roots = [path.join(OSS, 'plugins')]
  if (!OSS_ONLY) roots.push(path.join(ENT, 'plugins'))
  for (const root of roots) {
    const files = walk(root, (f) => f.endsWith('/config/menus.json'))
    for (const f of files) {
      const text = fs.readFileSync(f, 'utf8')
      let data
      try { data = JSON.parse(text) } catch { continue }
      const arr = Array.isArray(data) ? data : data.menus ?? []
      const lines = text.split('\n')
      for (const m of arr) {
        if (!m.permissionCode) continue
        const idx = lines.findIndex((l) => l.includes('"permissionCode"') && l.includes(`"${m.permissionCode}"`))
        refs.push({ file: f, line: idx >= 0 ? idx + 1 : 0, code: m.permissionCode, kind: 'menus.json.permissionCode' })
      }
    }
  }
  return refs
}

function relPath(absFile) {
  return path.relative(REPO_ROOT, absFile)
}

// Stable signature: ignores line number so reformat/edits don't churn.
function signature(hit) {
  return `${hit.file}\t${hit.kind}\t${hit.code}`
}

function loadBaseline(filePath) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`Baseline file not found: ${filePath}`)
  }
  const data = readJson(filePath)
  const set = new Set()
  for (const entry of data.entries ?? []) {
    set.add(`${entry.file}\t${entry.kind}\t${entry.code}`)
  }
  return { set, raw: data }
}

function writeBaseline(filePath, missing) {
  // missing[].file is already a repo-relative path (set in main()), so we
  // pass it through untouched — re-running relPath on a relative input would
  // resolve it against cwd and produce paths like "auraboot/.worktrees/.../...".
  const entries = missing
    .map((m) => ({ file: m.file, kind: m.kind, code: m.code }))
    .sort((a, b) =>
      a.file.localeCompare(b.file) || a.kind.localeCompare(b.kind) || a.code.localeCompare(b.code),
    )
  const payload = {
    generatedAt: new Date().toISOString(),
    note: 'Snapshot of known permission-code drift. To regenerate after a reconciliation phase, run the SAME command CI uses: `node scripts/validate-permission-codes.mjs --oss-only --write-baseline=scripts/permission-codes-baseline.json`. See docs/standards/meta/permission-code-naming.md.',
    mode: OSS_ONLY ? 'oss-only' : 'full',
    count: entries.length,
    entries,
  }
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, JSON.stringify(payload, null, 2) + '\n')
}

function main() {
  const { codes: registered, sources } = collectRegisteredCodes()
  const refs = [
    ...collectFrontendReferences(),
    ...collectJavaConstantValues(),
    ...collectMenuReferences(),
  ]
  const missing = refs
    .filter((r) => r.code !== '*' && !registered.has(r.code))
    .map((m) => ({ ...m, file: relPath(m.file) }))

  if (WRITE_BASELINE_PATH) {
    writeBaseline(path.resolve(WRITE_BASELINE_PATH), missing)
    if (JSON_OUT) {
      console.log(JSON.stringify({ wrote: WRITE_BASELINE_PATH, count: missing.length }, null, 2))
    } else {
      console.log(`[validate-permission-codes] wrote baseline: ${WRITE_BASELINE_PATH} (${missing.length} entries)`)
    }
    process.exit(0)
  }

  let baselineSet = null
  let baselineRaw = null
  if (BASELINE_PATH) {
    const loaded = loadBaseline(path.resolve(BASELINE_PATH))
    baselineSet = loaded.set
    baselineRaw = loaded.raw
  }

  const newDrift = baselineSet
    ? missing.filter((m) => !baselineSet.has(signature(m)))
    : missing
  const fixedFromBaseline = baselineSet
    ? [...baselineSet].filter((sig) => !missing.some((m) => signature(m) === sig))
    : []

  if (JSON_OUT) {
    console.log(JSON.stringify({
      registeredCount: registered.size,
      refs: refs.length,
      totalMissing: missing.length,
      baseline: baselineRaw ? { count: baselineRaw.count } : null,
      newDrift,
      fixedFromBaseline,
    }, null, 2))
    process.exit(newDrift.length > 0 ? 1 : 0)
  }

  console.log(`[validate-permission-codes] registered codes: ${registered.size} (from ${sources.length} sources)`)
  console.log(`[validate-permission-codes] references scanned: ${refs.length}`)
  if (baselineSet) {
    console.log(`[validate-permission-codes] baseline: ${baselineRaw.count} known drift entries from ${BASELINE_PATH}`)
  }
  console.log(`[validate-permission-codes] total drift: ${missing.length}; new (not in baseline): ${newDrift.length}`)

  if (fixedFromBaseline.length > 0) {
    console.log(`\n[validate-permission-codes] ${fixedFromBaseline.length} baseline entries are now resolved — consider regenerating baseline with --write-baseline.`)
  }

  if (newDrift.length === 0) {
    console.log('[validate-permission-codes] OK — no drift outside the baseline.')
    process.exit(0)
  }

  console.log(`\n[validate-permission-codes] NEW DRIFT — ${newDrift.length} reference(s) point at unregistered codes:\n`)
  for (const m of newDrift) {
    console.log(`  ${m.file}:${m.line}  [${m.kind}]  '${m.code}'`)
  }
  console.log('\nFix: register the code in default-bootstrap.json / plugins/*/config/permissions.json, or correct the reference to an existing code.')
  process.exit(1)
}

main()
