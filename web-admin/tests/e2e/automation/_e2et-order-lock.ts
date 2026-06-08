/**
 * Cross-process advisory lock for e2et_order automation specs.
 *
 * WHY: record-trigger automations (on_record_create / on_record_update / ...)
 * are MODEL-scoped — once enabled, they fire for EVERY record of `e2et_order`,
 * regardless of which test created it. The OSS Playwright config runs distinct
 * spec FILES in parallel across workers (`fullyParallel: false`, `workers: 4`),
 * so two automation files that both enable record-mutating automations on
 * `e2et_order` interfere:
 *
 *   automation-designer-golden H1 leaves an `on_record_create → update_record`
 *   automation enabled across its serial H1→H2→H3 chain. While it is enabled,
 *   automation-golden (parallel worker) creates an e2et_order record; the foreign
 *   automation fires an update_record on THAT record, which trips
 *   automation-golden's `on_record_update` trigger and attributes a log to the
 *   exact orderId — breaking `trigger-record-update`'s "a create must NOT fire
 *   on_record_update" assertion. triggerRecordId-scoping cannot help because the
 *   spurious update genuinely targets this test's own record.
 *
 * FIX: serialize the e2et_order-mutating automation files so at most one holds an
 * enabled record-mutating automation at any time. Each such file acquires this
 * lock in a top-level beforeAll and releases it in a top-level afterAll.
 *
 * Implementation: an atomic O_EXCL lockfile in the OS temp dir (shared by all
 * workers on the same machine). The holder writes its PID; a waiter that finds
 * the lock checks whether the holder process is still alive (process.kill(pid, 0))
 * and steals a lock left by a crashed worker. No heartbeat needed.
 */
import { existsSync, openSync, writeSync, closeSync, readFileSync, rmSync, statSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';

const LOCK_PATH = join(tmpdir(), 'aura-e2et-order-automation.lock');
// Hard ceiling on how long a waiter blocks before failing loudly (the slowest
// single automation file under heavy whole-suite load stays well under this).
const ACQUIRE_TIMEOUT_MS = 25 * 60 * 1000;
// A lock whose holder PID is dead AND whose file is older than this is stolen
// even if the dead-PID check is inconclusive (e.g. PID reuse on another host).
const HARD_STALE_MS = 30 * 60 * 1000;

function pidAlive(pid: number): boolean {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0); // signal 0 = liveness probe, does not actually signal
    return true;
  } catch (e) {
    // ESRCH = no such process (dead). EPERM = exists but not ours (alive).
    return (e as NodeJS.ErrnoException).code === 'EPERM';
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Acquire the exclusive e2et_order automation lock. Call in a top-level beforeAll. */
export async function acquireE2etOrderLock(label: string): Promise<void> {
  const start = Date.now();
  for (;;) {
    try {
      const fd = openSync(LOCK_PATH, 'wx'); // atomic exclusive create
      writeSync(fd, JSON.stringify({ pid: process.pid, label, at: Date.now() }));
      closeSync(fd);
      return;
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code !== 'EEXIST') throw err;

      // Lock is held — decide whether to wait or steal a crashed holder's lock.
      let steal = false;
      try {
        const raw = readFileSync(LOCK_PATH, 'utf-8');
        const meta = JSON.parse(raw) as { pid?: number; at?: number };
        if (typeof meta.pid === 'number' && !pidAlive(meta.pid)) {
          steal = true; // holder process is gone
        } else {
          const ageMs = Date.now() - statSync(LOCK_PATH).mtimeMs;
          if (ageMs > HARD_STALE_MS) steal = true; // belt-and-suspenders
        }
      } catch {
        // Partially-written / vanished lock — re-loop and retry the create.
      }

      if (steal) {
        rmSync(LOCK_PATH, { force: true });
        continue;
      }
      if (Date.now() - start > ACQUIRE_TIMEOUT_MS) {
        throw new Error(`[${label}] timed out acquiring e2et_order automation lock (${LOCK_PATH})`);
      }
      await sleep(250 + Math.floor(Math.random() * 250)); // jittered backoff
    }
  }
}

/** Release the lock (only if we still hold it). Call in a top-level afterAll. */
export function releaseE2etOrderLock(label: string): void {
  try {
    if (!existsSync(LOCK_PATH)) return;
    const meta = JSON.parse(readFileSync(LOCK_PATH, 'utf-8')) as { pid?: number };
    // Only delete a lock this process owns — never clobber another holder's lock.
    if (meta.pid === process.pid) rmSync(LOCK_PATH, { force: true });
  } catch {
    // If the lock is unreadable but ours by convention, best-effort remove.
    try {
      rmSync(LOCK_PATH, { force: true });
    } catch {
      /* ignore */
    }
  }
  void label;
}
