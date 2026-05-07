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
 *   - auraboot-enterprise/plugins/*\/config/permissions.json
 *
 * References scanned:
 *   - auraboot/web-admin/app/plugins/*\/resources.ts
 *     -> object literal field `permission: '<code>'`.
 *   - auraboot/platform/src/main/java/com/auraboot/framework/permission/constants/*.java
 *     -> string literal values of `public static final String X = "...";`.
 *   - {oss,enterprise}/plugins/*\/config/menus.json `permissionCode`.
 *
 * Wildcard `*` is allowed (role binding wildcards).
 *
 * Usage:
 *   node scripts/validate-permission-codes.mjs            # human report, exit 1 on drift
 *   node scripts/validate-permission-codes.mjs --json     # machine-readable
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)

// Walk upward looking for a parent that contains BOTH `auraboot` and
// `auraboot-enterprise` siblings — works from a normal checkout
// (.../auraboot/scripts/...) and from a git worktree
// (.../auraboot/.worktrees/<branch>/scripts/...).
function findRepoRoot(start) {
  let cur = path.dirname(start)
  for (let i = 0; i < 10; i++) {
    if (
      fs.existsSync(path.join(cur, 'auraboot')) &&
      fs.existsSync(path.join(cur, 'auraboot-enterprise'))
    ) return cur
    const parent = path.dirname(cur)
    if (parent === cur) break
    cur = parent
  }
  throw new Error('Could not locate repo root containing auraboot + auraboot-enterprise')
}

const REPO_ROOT = findRepoRoot(__filename)
const OSS = path.join(REPO_ROOT, 'auraboot')
const ENT = path.join(REPO_ROOT, 'auraboot-enterprise')

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function walk(dir, predicate, hits = []) {
  if (!fs.existsSync(dir)) return hits
  for (const name of fs.readdirSync(dir)) {
    if (name === 'node_modules' || name === 'build' || name === 'dist' || name === 'target' || name === '.git' || name === '.worktrees') continue
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

  for (const root of [path.join(OSS, 'plugins'), path.join(ENT, 'plugins')]) {
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
  for (const root of [path.join(OSS, 'plugins'), path.join(ENT, 'plugins')]) {
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

function main() {
  const json = process.argv.includes('--json')
  const { codes: registered, sources } = collectRegisteredCodes()
  const refs = [
    ...collectFrontendReferences(),
    ...collectJavaConstantValues(),
    ...collectMenuReferences(),
  ]
  const missing = refs.filter((r) => r.code !== '*' && !registered.has(r.code))

  if (json) {
    console.log(JSON.stringify({ registeredCount: registered.size, refs: refs.length, missing }, null, 2))
    process.exit(missing.length > 0 ? 1 : 0)
  }

  console.log(`[validate-permission-codes] registered codes: ${registered.size} (from ${sources.length} sources)`)
  console.log(`[validate-permission-codes] references scanned: ${refs.length}`)
  if (missing.length === 0) {
    console.log('[validate-permission-codes] OK — every reference resolves to a registered code')
    process.exit(0)
  }
  console.log(`[validate-permission-codes] DRIFT — ${missing.length} reference(s) point at unregistered codes:\n`)
  for (const m of missing) {
    const rel = path.relative(REPO_ROOT, m.file)
    console.log(`  ${rel}:${m.line}  [${m.kind}]  '${m.code}'`)
  }
  console.log('\nFix: register the code in default-bootstrap.json / plugins/*/config/permissions.json, or correct the reference to an existing code.')
  process.exit(1)
}

main()
