#!/usr/bin/env node
/**
 * check-cache-eviction — every @Cacheable cache must have *someone* who evicts it.
 *
 * Why (OSS #1226): `dictData` sat behind a 30-minute Caffeine cache and every dict edit —
 * from the admin UI and from plugin import alike — landed in the database and then went
 * unseen until the process restarted. Nothing failed. It just quietly served stale data.
 *
 * ## What this gate DOES catch
 *
 *   A cache used by @Cacheable with **no @CacheEvict anywhere**, or whose only evictors are
 *   dedicated `clearXxx()/evictAll()` methods that **no production code ever calls**.
 *   That is the common omission: add the cache, forget the invalidation entirely.
 *
 * ## What this gate does NOT catch — read this before trusting it
 *
 *   It cannot tell you that *every write path* of the cached entity evicts. dictData is
 *   the proof: even before #1226 it had an evictor (`switchCurrentVersion`, a rarely used
 *   version operation), while the **main CRUD write paths did not evict at all**. The
 *   @Cacheable lives in DictVersionServiceImpl and the writes live in DictServiceImpl —
 *   a cross-class relationship static analysis cannot resolve without guessing.
 *
 *   So: a green run here means "somebody evicts this cache", NOT "your write is visible".
 *   That second property is only provable by a test of the shape:
 *       read (populate cache) → write → read again → assert the change is visible.
 *   See engineering-gotchas/backend-spring-db.md §写路径不 evict 读缓存.
 *
 * This gate was itself falsifiable-tested: stripping the @CacheEvict annotations off
 * DictServiceImpl must make it fail. An earlier draft counted an *interface declaration*
 * as a "caller" and was therefore unfalsifiable — green no matter what. Keep the
 * `hasProductionCaller` receiver requirement (`.name(`), and keep the test in
 * `docs/` honest: a gate you have never seen go red is not a gate.
 */
import { readFileSync } from 'node:fs';
import { execSync } from 'node:child_process';
import path from 'node:path';

const REPO = path.resolve(new URL('..', import.meta.url).pathname);
const MAIN = path.join(REPO, 'platform/src/main');

/**
 * Caches whose staleness (up to the Caffeine TTL) is a deliberate trade-off.
 * A reason is mandatory — "we never got around to it" is not a reason.
 */
const ALLOWLIST = {
  // Empty, and that is the point: both entries that lived here (aggregateQuery,
  // dataFilterResult) were found BY this gate and have since been fixed —
  // aggregateQuery by evicting on every dynamic-data write (AggregateQueryCacheInterceptor),
  // dataFilterResult by dropping a cache whose key could not have been correct.
  // An allowlist entry is a promise to come back, not a place to park a defect.
};

const files = execSync(`find ${MAIN} -name '*.java'`, { encoding: 'utf8' })
  .trim()
  .split('\n')
  .filter(Boolean);

/**
 * Strip comments before analysing. A javadoc explaining *why a cache was removed* legitimately
 * mentions `@Cacheable("thatCache")`, and an earlier version of this gate read that prose as a
 * live annotation and demanded eviction for a cache that no longer exists.
 */
const stripComments = (s) =>
  s.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');
const src = new Map(files.map((f) => [f, stripComments(readFileSync(f, 'utf8'))]));

const cacheables = new Set();
/**
 * @Cacheable(value = SOME_CONSTANT) — the cache name is a constant, not a literal, so this
 * gate cannot see it. Reported rather than silently skipped: a blind spot you know about is
 * a different thing from one you don't. (CommandMetadataCacheService does this.)
 */
const opaque = new Set();
for (const [f, s] of src) {
  for (const m of s.matchAll(/@Cacheable\s*\(((?:[^()]|\([^()]*\))*)\)/g)) {
    const literals = [...m[1].matchAll(/"([A-Za-z][A-Za-z0-9_]*)"/g)].map((q) => q[1]);
    if (literals.length) literals.forEach((l) => cacheables.add(l));
    else if (/value\s*=\s*[A-Z_]{3,}/.test(m[1]) || /^\s*[A-Z_]{3,}/.test(m[1])) {
      opaque.add(path.basename(f));
    }
  }
}

const ANNOTATED_METHOD =
  /@(?:CacheEvict|Caching)\s*\((?<ann>(?:[^()]|\((?:[^()]|\([^()]*\))*\))*)\)(?<between>(?:\s*@\w+(?:\s*\((?:[^()]|\([^()]*\))*\))?)*)\s*(?:public|protected)\s+[\w<>\[\],.\s]+?\s+(?<name>\w+)\s*\(/g;

function evictorsOf(cache) {
  const out = [];
  for (const [f, s] of src) {
    for (const m of s.matchAll(ANNOTATED_METHOD)) {
      if (m.groups.ann.includes(`"${cache}"`)) out.push({ file: f, name: m.groups.name });
    }
  }
  return out;
}

/**
 * A real call site outside the declaring file. Requires a receiver (`x.name(`) so that an
 * interface declaration (`void clearDictCache(String code);`) is not mistaken for a caller
 * — that mistake made an earlier draft of this gate unfalsifiable.
 */
function hasProductionCaller(name, declaringFile) {
  const CALL = new RegExp(`\\.\\s*${name}\\s*\\(`);
  for (const [f, s] of src) {
    if (f === declaringFile) continue;
    if (CALL.test(s)) return true;
  }
  return false;
}

/**
 * Eviction does not have to be an annotation. A MyBatis interceptor calling
 * `cacheManager.getCache("aggregateQuery").clear()` on every dynamic-data write is a
 * perfectly good — and in that case, the only viable — invalidation path: the write happens
 * from 121 call sites across 39 classes, so no annotation could have covered it.
 * Requiring @CacheEvict specifically would push people toward the wrong design.
 */
function hasManualEviction(cache) {
  for (const s of src.values()) {
    if (!s.includes(`getCache(`) || !s.includes(`"${cache}"`)) continue;
    if (/\.clear\(\)|\.evict\(/.test(s)) return true;
  }
  return false;
}

const unreachable = [];
const allowed = [];
for (const cache of [...cacheables].sort()) {
  if (hasManualEviction(cache)) continue;
  const evictors = evictorsOf(cache);
  // Reachable = someone can actually trigger an eviction: either the annotation sits on a
  // method other code calls, or on a method that is itself reachable from a caller.
  const reachable = evictors.some((e) => hasProductionCaller(e.name, e.file));
  if (reachable) continue;
  const why = evictors.length
    ? `@CacheEvict exists but nothing calls it: ${evictors.map((e) => `${e.name}()`).join(', ')}`
    : 'no @CacheEvict anywhere — a write to this data is never made visible';
  (ALLOWLIST[cache] ? allowed : unreachable).push({ cache, why });
}

console.log(`Scanned ${files.length} java file(s); ${cacheables.size} @Cacheable cache(s).`);
for (const { cache } of allowed) console.log(`  ~ ${cache} — allowlisted: ${ALLOWLIST[cache]}`);
if (opaque.size) {
  console.log(
    `  ! blind spot: ${opaque.size} class(es) declare @Cacheable with a constant cache name ` +
      `(${[...opaque].join(', ')}) — not analysable here, review their eviction by hand.`,
  );
}

if (unreachable.length === 0) {
  console.log('✅ every @Cacheable cache has someone who evicts it.');
  console.log(
    '   (NOTE: this does not prove your write is visible — only a read→write→read test does.)',
  );
  process.exit(0);
}

console.error('\n❌ Caches nobody evicts — writes to this data will not be visible to readers:\n');
for (const { cache, why } of unreachable) {
  console.error(`  ${cache}\n      ${why}`);
  console.error(
    `      Fix: @CacheEvict(value = "${cache}", allEntries = true) on the public write methods`,
  );
  console.error(
    `      themselves (self-invocation skips the Spring proxy — annotate each one, not just a`,
  );
  console.error(
    `      shared helper). If staleness up to the TTL is genuinely intended, add "${cache}" to`,
  );
  console.error(`      ALLOWLIST with a reason.\n`);
}
process.exit(1);
