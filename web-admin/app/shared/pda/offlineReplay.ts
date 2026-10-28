/**
 * PDA Offline Shell — ServiceWorker registration + auto replay executor (WMS D3).
 *
 * Semantics follow plugins#779 (`inv_pda_offline_queue`):
 *  - The ServiceWorker (public/sw.js) keeps the five PDA page shells reachable
 *    offline and captures enqueue POSTs that fail at the network level into the
 *    `pda-offline-outbox` cache.
 *  - On reconnect this executor drains the outbox (flushing the original
 *    enqueue commands so each capture becomes an auditable queue row), then
 *    sweeps queued rows in order: the stored command is re-executed with the
 *    row's `inv_pq_create_key` as clientRequestId — the platform create_key
 *    idempotency dedups the operator's own retries, while the underlying task
 *    row_version CAS rejects when another device already processed the task
 *    (绝不覆盖他人结果). Outcomes are recorded via inv:pda_mark_replayed /
 *    inv:pda_mark_rejected. The executor itself never touches inventory.
 *
 * Registration failure or unsupported browsers degrade silently (e2e-truth:
 * the shell reports `registered:false` instead of pretending offline works).
 */

const OUTBOX_CACHE = 'pda-offline-outbox';
const ENQUEUE_COMMAND = 'inv:pda_queue_offline';
const MARK_REPLAYED_COMMAND = 'inv:pda_mark_replayed';
const MARK_REJECTED_COMMAND = 'inv:pda_mark_rejected';
const QUEUE_MODEL = 'inv_pda_offline_queue';
const COMMAND_EXECUTE_PREFIX = '/api/meta/commands/execute/';

/** PDA command → queue kind (inv_pq_kind). Unknown commands stay generic. */
function kindForCommand(commandCode: string): string {
  if (commandCode.startsWith('inv:putaway_confirm')) return 'putaway';
  if (commandCode.startsWith('inv:record_issue_pick')) return 'pick';
  if (commandCode.startsWith('inv:confirm_warehouse_in')) return 'receive';
  if (commandCode.startsWith('inv:submit_stock_check')) return 'stockcheck';
  return 'generic';
}

interface OutboxEntry {
  capturedAt: string;
  commandCode?: string;
  url?: string;
  headers?: Record<string, string>;
  body?: string;
}

export interface PdaOfflineShellStatus {
  serviceWorkerSupported: boolean;
  registered: boolean;
  replaying: boolean;
  lastReplayAt: string | null;
  lastError: string | null;
}

const status: PdaOfflineShellStatus = {
  serviceWorkerSupported: false,
  registered: false,
  replaying: false,
  lastReplayAt: null,
  lastError: null,
};

let started = false;

export function startPdaOfflineShell(): PdaOfflineShellStatus {
  if (started || typeof window === 'undefined') return status;
  started = true;

  const runWhenOnline = () => {
    if (navigator.onLine) void replayNow('online-event');
  };
  window.addEventListener('online', runWhenOnline);
  window.addEventListener('message', (event) => {
    if ((event.data as { type?: string } | null)?.type === 'PDA_OUTBOX_STASHED') {
      void replayNow('sw-outbox-stashed');
    }
  });
  // Late sweep for rows queued while this tab was closed/reloading.
  window.setTimeout(() => {
    if (navigator.onLine) void replayNow('startup');
  }, 3000);

  void registerServiceWorker();

  (window as unknown as Record<string, unknown>).__pdaOfflineShell = {
    status,
    replayNow,
  };
  return status;
}

export async function registerServiceWorker(): Promise<boolean> {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
    return false;
  }
  status.serviceWorkerSupported = true;
  try {
    const reg = await navigator.serviceWorker.register('/sw.js');
    status.registered = Boolean(reg);
    return status.registered;
  } catch (err) {
    // Honest degradation: no SW, no offline shell claim.
    status.lastError = `serviceWorker register failed: ${String(err)}`;
    return false;
  }
}

/** Drain the offline outbox, then replay queued rows once, in queue order. */
export async function replayNow(trigger: string): Promise<PdaOfflineShellStatus> {
  if (status.replaying) return status;
  status.replaying = true;
  try {
    const drained = await drainOutbox();
    if (drained > 0) {
      console.info(`[pda-offline] drained ${drained} offline-captured enqueue(s) (trigger=${trigger})`);
    }
    const replayed = await sweepQueue();
    if (replayed.total > 0) {
      console.info(
        `[pda-offline] sweep (trigger=${trigger}): ${replayed.replayed} replayed, ${replayed.rejected} rejected`,
      );
    }
  } catch (err) {
    status.lastError = String(err);
  } finally {
    status.replaying = false;
  }
  return status;
}

async function executeCommand(
  command: string,
  body: Record<string, unknown>,
): Promise<{ code: string; message: string; data: Record<string, unknown> | null }> {
  const { fetchResult } = await import('~/shared/services/http-client/HttpClient');
  const result = await fetchResult<Record<string, unknown>>(
    `/api/meta/commands/execute/${command}`,
    { method: 'post', body },
  );
  return {
    code: String(result.code ?? 'unknown'),
    message: String(result.message ?? ''),
    data: (result.data as Record<string, unknown> | null) ?? null,
  };
}

async function drainOutbox(): Promise<number> {
  if (typeof caches === 'undefined') return 0;
  const cache = await caches.open(OUTBOX_CACHE);
  const keys = await cache.keys();
  let drained = 0;
  for (const key of keys) {
    const cached = await cache.match(key);
    if (!cached) continue;
    let entry: OutboxEntry;
    try {
      entry = (await cached.json()) as OutboxEntry;
    } catch {
      await cache.delete(key);
      continue;
    }
    try {
      const outcome = await flushOutboxEntry(entry);
      // Flushed: the audit row now exists server-side (or the capture was
      // durably rejected). Delete either way so a broken capture cannot loop.
      await cache.delete(key);
      if (outcome) drained += 1;
    } catch {
      // Still offline — keep the entry for the next online transition.
    }
  }
  return drained;
}

/** Turn one offline capture into an auditable queue row (照 inv_pda_offline_queue
 *  入队命令). The captured enqueue executes verbatim; a captured PDA command is
 *  enqueued as kind/command/target/payload snapshot and replayed afterwards by
 *  sweepQueue with the row's create_key. */
async function flushOutboxEntry(entry: OutboxEntry): Promise<boolean> {
  if (!entry.url || typeof entry.body !== 'string') return false;
  const commandCode = String(entry.commandCode ?? '');

  if (commandCode === ENQUEUE_COMMAND) {
    const res = await fetch(entry.url, {
      method: 'POST',
      headers: entry.headers ?? { 'Content-Type': 'application/json' },
      body: entry.body,
    });
    const json = (await res.json().catch(() => ({}))) as { code?: string };
    return json.code === '0';
  }

  let original: { payload?: unknown; targetRecordPid?: unknown } = {};
  try {
    original = JSON.parse(entry.body) as typeof original;
  } catch {
    original = {};
  }
  const payload = (original.payload && typeof original.payload === 'object' ? original.payload : {}) as Record<string, unknown>;
  const targetPid = String(original.targetRecordPid ?? '');
  const enqueue = await executeCommand(ENQUEUE_COMMAND, {
    payload: {
      inv_pq_kind: kindForCommand(commandCode),
      inv_pq_command: commandCode,
      inv_pq_target_pid: targetPid,
      inv_pq_payload: JSON.stringify(payload),
    },
    operationType: 'CREATE',
  });
  if (enqueue.code !== '0') {
    console.warn(`[pda-offline] enqueue of captured ${commandCode} failed: ${enqueue.message}`);
  }
  return enqueue.code === '0';
}

interface QueueRow {
  pid: string;
  created_at?: string;
  inv_pq_kind?: string;
  inv_pq_command?: string;
  inv_pq_target_pid?: string;
  inv_pq_payload?: string;
  inv_pq_create_key?: string;
  inv_pq_status?: string;
}

async function sweepQueue(): Promise<{ total: number; replayed: number; rejected: number }> {
  const { fetchResult } = await import('~/shared/services/http-client/HttpClient');
  const listed = await fetchResult<{ records?: QueueRow[] }>(
    `/api/dynamic/${QUEUE_MODEL}/list`,
    { method: 'get', params: { pageNum: 1, pageSize: 100 } },
  );
  const records = listed.data?.records ?? [];
  // Recency guard: the auto executor only claims fresh rows (last 24h). Older
  // queued rows predate the shell and are left for explicit operator action.
  const freshCutoff = Date.now() - 24 * 3600 * 1000;
  const queued = records
    .filter((r) => r.inv_pq_status === 'queued')
    .filter((r) => {
      const t = Date.parse(String(r.created_at ?? ''));
      return Number.isNaN(t) ? true : t >= freshCutoff;
    })
    .sort((a, b) => String(a.pid).localeCompare(String(b.pid)));

  let replayed = 0;
  let rejected = 0;
  for (const row of queued) {
    const outcome = await replayRow(row);
    if (outcome === 'replayed') replayed += 1;
    if (outcome === 'rejected') rejected += 1;
  }
  return { total: queued.length, replayed, rejected };
}

async function replayRow(row: QueueRow): Promise<'replayed' | 'rejected' | 'skipped'> {
  const command = String(row.inv_pq_command ?? '');
  const targetPid = String(row.inv_pq_target_pid ?? '');
  const createKey = String(row.inv_pq_create_key ?? '');
  if (!command || !createKey) return 'skipped';

  let payload = parsePayload(row.inv_pq_payload);
  if (isEmptyPayload(payload)) {
    const resolved = await resolveDefaultPayload(String(row.inv_pq_kind ?? ''), targetPid);
    if (resolved === null) {
      // Cannot invent the operator's actual payload (e.g. picked quantity) —
      // leave the row queued instead of guessing.
      console.warn(`[pda-offline] row ${row.pid} (${row.inv_pq_kind}) has no payload snapshot; left queued`);
      return 'skipped';
    }
    payload = resolved;
  }

  const replay = await executeCommand(command, {
    clientRequestId: createKey,
    payload,
    ...(targetPid ? { targetRecordPid: targetPid, operationType: 'update' } : {}),
  });

  if (replay.code === '0') {
    await executeCommand(MARK_REPLAYED_COMMAND, {
      payload: { inv_pq_replayed_at: new Date().toISOString() },
      targetRecordPid: row.pid,
      operationType: 'update',
    });
    return 'replayed';
  }

  const reason = replay.message || replay.code || 'replay rejected';
  await executeCommand(MARK_REJECTED_COMMAND, {
    payload: { inv_pq_reject_reason: reason.slice(0, 480) },
    targetRecordPid: row.pid,
    operationType: 'update',
  });
  return 'rejected';
}

/** Payload-less rows: re-derive from the live task so replay stays truthful. */
async function resolveDefaultPayload(kind: string, targetPid: string): Promise<Record<string, unknown> | null> {
  if (kind === 'receive' || kind === 'stockcheck') return {};
  if (kind === 'putaway' && targetPid) {
    const { fetchResult } = await import('~/shared/services/http-client/HttpClient');
    const task = await fetchResult<Record<string, unknown>>(`/api/dynamic/inv_putaway_task/${targetPid}`, { method: 'get' });
    const row = (task.data ?? {}) as Record<string, unknown>;
    const qty = row.inv_pt_remaining_qty ?? row.inv_pt_required_qty;
    const loc = row.inv_pt_suggested_location_id;
    if (qty != null && loc) {
      return { inv_pt_confirm_qty: qty, inv_pt_target_location_id: loc };
    }
  }
  return null;
}

function parsePayload(raw: unknown): Record<string, unknown> {
  if (typeof raw !== 'string' || !raw.trim()) return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function isEmptyPayload(payload: Record<string, unknown>): boolean {
  return Object.keys(payload).length === 0;
}
