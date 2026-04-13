#!/usr/bin/env node
/**
 * verify-plugin-routes — guards against drift between a plugin's
 * NavigationResource.path declarations and its routes.ts route() entries.
 *
 * The dual-write exists because React Router 7 typegen requires static
 * route() calls for codegen, while runtime menu/breadcrumb derivation
 * needs NavigationResource objects. M5 will eliminate the dual-write via
 * build-time codegen; this script keeps them aligned in the meantime.
 *
 * Failure mode: a plugin author updates one file but not the other →
 * runtime menu shows a route the router can't actually serve, or vice
 * versa. This script catches it as a build-time error.
 *
 * Approach (simple text scanning, no AST):
 *   - For each app/plugins/core-* directory:
 *     - Extract `path: '...'` literals from index.ts
 *     - Extract `route('...', ...)` literals from routes.ts
 *     - Compare the two sets; report mismatches
 *
 * Usage:
 *   node scripts/verify-plugin-routes.mjs
 *   pnpm verify:plugin-routes        (added to package.json)
 *
 * Exit codes: 0 if all aligned, 1 on mismatch.
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const PLUGINS_DIR = path.resolve(__dirname, '../app/plugins')

const RED = '\x1b[31m'
const GREEN = '\x1b[32m'
const YELLOW = '\x1b[33m'
const RESET = '\x1b[0m'

function extractPathsFromIndex(content) {
  // Find `path: '...'` and check a window of ~400 chars around each match
  // for `loader:` to detect dynamically-loaded resources (which don't need
  // a static route() entry).
  const paths = new Set()
  const dynamicLoaderPaths = new Set()
  const re = /\bpath\s*:\s*['"]([^'"]+)['"]/g
  let m
  while ((m = re.exec(content)) !== null) {
    const p = m[1]
    paths.add(p)
    const start = Math.max(0, m.index - 400)
    const end = Math.min(content.length, m.index + 400)
    const window = content.slice(start, end)
    if (/\bloader\s*:/.test(window)) {
      dynamicLoaderPaths.add(p)
    }
  }
  return { paths, dynamicLoaderPaths }
}

function extractPathsFromRoutes(content) {
  // Match `route('...', '...')` calls.
  const paths = new Set()
  const re = /\broute\s*\(\s*['"]([^'"]+)['"]\s*,/g
  let m
  while ((m = re.exec(content)) !== null) {
    paths.add(m[1])
  }
  return paths
}

function readIfExists(p) {
  try {
    return fs.readFileSync(p, 'utf8')
  } catch {
    return null
  }
}

function main() {
  if (!fs.existsSync(PLUGINS_DIR)) {
    console.error(`${RED}plugins dir not found: ${PLUGINS_DIR}${RESET}`)
    process.exit(1)
  }

  const entries = fs.readdirSync(PLUGINS_DIR, { withFileTypes: true })
    .filter(e => e.isDirectory() && /^core-/.test(e.name))
    .map(e => e.name)
    .sort()

  let ok = 0
  let fail = 0
  let skipped = 0

  for (const name of entries) {
    const dir = path.join(PLUGINS_DIR, name)
    const indexFile = path.join(dir, 'index.ts')
    const routesFile = path.join(dir, 'routes.ts')

    const indexSrc = readIfExists(indexFile)
    const routesSrc = readIfExists(routesFile)

    if (!indexSrc) {
      console.log(`${YELLOW}[skip]${RESET}    ${name}: no index.ts`)
      skipped++
      continue
    }
    if (!routesSrc) {
      // Plugins without routes.ts are valid (register no routes), only
      // verify if NavigationResources reference paths without dynamic loaders.
      const { paths: indexPaths, dynamicLoaderPaths } = extractPathsFromIndex(indexSrc)
      const staticPaths = [...indexPaths].filter(p => !dynamicLoaderPaths.has(p))
      if (staticPaths.length === 0) {
        const dyn = dynamicLoaderPaths.size > 0 ? ` (${dynamicLoaderPaths.size} dynamic loader)` : ' (no routes)'
        console.log(`${GREEN}[ ok ]${RESET}    ${name}: no static routes${dyn}`)
        ok++
      } else {
        console.log(`${RED}[fail]${RESET}    ${name}: declares ${staticPaths.length} static NavigationResource path(s) but has no routes.ts`)
        for (const p of staticPaths) console.log(`         - ${p}`)
        fail++
      }
      continue
    }

    const { paths: indexPaths, dynamicLoaderPaths } = extractPathsFromIndex(indexSrc)
    const routesPaths = extractPathsFromRoutes(routesSrc)

    // FAIL: NavigationResource declares a path but no matching route() exists,
    // and the resource doesn't use a dynamic loader.
    // OK:   route() exists without a NavigationResource (detail/edit/new sub-pages
    //       not in the sidebar are valid).
    const missingInRoutes = [...indexPaths]
      .filter(p => !routesPaths.has(p) && !dynamicLoaderPaths.has(p))

    if (missingInRoutes.length === 0) {
      const sub = routesPaths.size - indexPaths.size
      const subNote = sub > 0 ? ` (${sub} sub-route${sub > 1 ? 's' : ''} without nav entry)` : ''
      console.log(`${GREEN}[ ok ]${RESET}    ${name}: ${indexPaths.size} nav · ${routesPaths.size} routes${subNote}`)
      ok++
    } else {
      console.log(`${RED}[fail]${RESET}    ${name}: nav paths missing from routes.ts`)
      for (const p of missingInRoutes) console.log(`         - ${p}`)
      fail++
    }
  }

  console.log()
  console.log(`${ok} ok · ${skipped} skipped · ${fail} fail`)
  process.exit(fail === 0 ? 0 : 1)
}

main()
