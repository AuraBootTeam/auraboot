/**
 * Cross-process advisory lock for e2et_order saved-view specs.
 *
 * WHY: saved-view state (the per-user-per-model "active view", personal/implicit
 * views, and view selection) is shared across every spec that operates on the
 * `e2et_order` model under the shared admin storageState. The OSS Playwright config
 * runs distinct spec FILES in parallel across workers, so two saved-view files that
 * both create / select / delete e2et_order views interfere:
 *
 *   saved-view-calendar leaves an "E2E Calendar View" as the active view; while it
 *   is active, saved-view-button-field opens the list and gets the calendar grid
 *   instead of a table, so the table-only RowHeightSelector never mounts. Likewise
 *   saved-view-gantt/kanban/management select a view BY NAME that a parallel file
 *   creates-then-deletes mid-run, so `selectSavedViewByName(...)` returns false.
 *   These races cannot be scoped away per-record — the shared resource is the
 *   model's per-user view state itself.
 *
 * FIX: serialize the e2et_order saved-view files so at most one manipulates the
 * model's view state at any time. Each file acquires this lock in a top-level
 * beforeAll and releases it in a top-level afterAll. Under workers=N the other
 * workers keep running non-saved-view specs, so the gate does not stall.
 *
 * Mirrors tests/e2e/automation/_e2et-order-lock.ts (same O_EXCL lockfile pattern,
 * a distinct lock path so saved-view and automation serialization are independent).
 */
import { existsSync, openSync, writeSync, closeSync, readFileSync, rmSync, statSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';

const LOCK_PATH = join(tmpdir(), 'aura-e2et-order-savedview.lock');
const ACQUIRE_TIMEOUT_MS = 25 * 60 * 1000;
const HARD_STALE_MS = 30 * 60 * 1000;

function pidAlive(pid: number): boolean {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === 'EPERM';
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Acquire the exclusive e2et_order saved-view lock. Call in a top-level beforeAll. */
export async function acquireSavedViewLock(label: string): Promise<void> {
  const start = Date.now();
  for (;;) {
    try {
      const fd = openSync(LOCK_PATH, 'wx');
      writeSync(fd, JSON.stringify({ pid: process.pid, label, at: Date.now() }));
      closeSync(fd);
      return;
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code !== 'EEXIST') throw err;

      let steal = false;
      try {
        const raw = readFileSync(LOCK_PATH, 'utf-8');
        const meta = JSON.parse(raw) as { pid?: number; at?: number };
        if (typeof meta.pid === 'number' && !pidAlive(meta.pid)) {
          steal = true;
        } else {
          const ageMs = Date.now() - statSync(LOCK_PATH).mtimeMs;
          if (ageMs > HARD_STALE_MS) steal = true;
        }
      } catch {
        /* partially-written / vanished lock — re-loop */
      }

      if (steal) {
        rmSync(LOCK_PATH, { force: true });
        continue;
      }
      if (Date.now() - start > ACQUIRE_TIMEOUT_MS) {
        throw new Error(`[${label}] timed out acquiring e2et_order saved-view lock (${LOCK_PATH})`);
      }
      await sleep(250 + Math.floor(Math.random() * 250));
    }
  }
}

/** Release the lock (only if we still hold it). Call in a top-level afterAll. */
export function releaseSavedViewLock(label: string): void {
  try {
    if (!existsSync(LOCK_PATH)) return;
    const meta = JSON.parse(readFileSync(LOCK_PATH, 'utf-8')) as { pid?: number };
    if (meta.pid === process.pid) rmSync(LOCK_PATH, { force: true });
  } catch {
    try {
      rmSync(LOCK_PATH, { force: true });
    } catch {
      /* ignore */
    }
  }
  void label;
}
