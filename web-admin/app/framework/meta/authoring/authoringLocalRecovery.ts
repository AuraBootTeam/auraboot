import type { PageSchemaV3 } from '~/plugins/core-designer/components/unified-designer/types';
import type { PendingAuthoringEdit } from './types';

const STORAGE_PREFIX = 'auraboot:authoring-recovery:v2';
const LEGACY_STORAGE_PREFIX = 'auraboot:authoring-recovery:v1';
const RECOVERY_MAX_AGE_MS = 24 * 60 * 60 * 1000;
const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000;

export type AuthoringRecoveryPolicy = 'PERSISTENT' | 'SESSION_ONLY' | 'DISABLED';
type RecoveryState = 'DIRTY' | 'UNKNOWN_OUTCOME';

interface AuthoringRecoveryBase {
  version: 2;
  actorId: string;
  sessionPid: string;
  pagePid: string;
  baseRevision: number;
  savedAt: number;
  state: RecoveryState;
}

export interface InlineAuthoringRecovery extends AuthoringRecoveryBase {
  kind: 'INLINE';
  edits: PendingAuthoringEdit[];
}

export interface StudioAuthoringRecovery extends AuthoringRecoveryBase {
  kind: 'STUDIO';
  baseSnapshot: Record<string, unknown>;
  mineDocument: PageSchemaV3;
}

export function storeInlineAuthoringRecovery(
  recovery: Omit<InlineAuthoringRecovery, 'version' | 'kind' | 'savedAt'>,
  policy: AuthoringRecoveryPolicy = 'PERSISTENT',
): boolean {
  return writeRecovery(
    inlineRecoveryKey(recovery.actorId, recovery.pagePid, recovery.sessionPid),
    {
      ...recovery,
      version: 2,
      kind: 'INLINE',
      savedAt: Date.now(),
    },
    policy,
  );
}

/**
 * Returns an exact session when supplied, otherwise the newest valid recovery candidate for the
 * actor/page. Candidate selection never merges or replays records from separate writer sessions.
 */
export function readInlineAuthoringRecovery(
  actorId: string,
  pagePid: string,
  expectedSessionPid?: string,
  policy: AuthoringRecoveryPolicy = 'PERSISTENT',
): InlineAuthoringRecovery | null {
  return (
    listInlineAuthoringRecoveries(actorId, pagePid, policy).find(
      (recovery) => !expectedSessionPid || recovery.sessionPid === expectedSessionPid,
    ) ?? null
  );
}

export function listInlineAuthoringRecoveries(
  actorId: string,
  pagePid: string,
  policy: AuthoringRecoveryPolicy = 'PERSISTENT',
): InlineAuthoringRecovery[] {
  const storage = recoveryStorage(policy);
  if (!storage) return [];
  const prefix = inlineRecoveryPrefix(actorId, pagePid);
  return listStorageKeys(storage, prefix)
    .map((key) => ({ key, recovery: readRecovery(storage, key) }))
    .filter((candidate): candidate is { key: string; recovery: InlineAuthoringRecovery } => {
      const valid =
        isInlineRecovery(candidate.recovery) &&
        candidate.recovery.actorId === actorId &&
        candidate.recovery.pagePid === pagePid;
      if (!valid) removeRecovery(storage, candidate.key);
      return valid;
    })
    .map(({ recovery }) => recovery)
    .sort((left, right) => right.savedAt - left.savedAt);
}

export function clearInlineAuthoringRecovery(
  actorId: string,
  pagePid: string,
  expectedSessionPid?: string,
): void {
  forEachRecoveryStorage((storage) => {
    if (expectedSessionPid) {
      removeRecovery(storage, inlineRecoveryKey(actorId, pagePid, expectedSessionPid));
      return;
    }
    listStorageKeys(storage, inlineRecoveryPrefix(actorId, pagePid)).forEach((key) =>
      removeRecovery(storage, key),
    );
  });
}

export function storeStudioAuthoringRecovery(
  recovery: Omit<StudioAuthoringRecovery, 'version' | 'kind' | 'savedAt'>,
  policy: AuthoringRecoveryPolicy = 'PERSISTENT',
): boolean {
  return writeRecovery(
    studioRecoveryKey(recovery.actorId, recovery.sessionPid),
    {
      ...recovery,
      version: 2,
      kind: 'STUDIO',
      savedAt: Date.now(),
    },
    policy,
  );
}

export function readStudioAuthoringRecovery(
  actorId: string,
  sessionPid: string,
  policy: AuthoringRecoveryPolicy = 'PERSISTENT',
): StudioAuthoringRecovery | null {
  const storage = recoveryStorage(policy);
  if (!storage) return null;
  const key = studioRecoveryKey(actorId, sessionPid);
  const recovery = readRecovery(storage, key);
  if (
    !isStudioRecovery(recovery) ||
    recovery.actorId !== actorId ||
    recovery.sessionPid !== sessionPid
  ) {
    removeRecovery(storage, key);
    return null;
  }
  return recovery;
}

export function clearStudioAuthoringRecovery(actorId: string, sessionPid: string): void {
  forEachRecoveryStorage((storage) =>
    removeRecovery(storage, studioRecoveryKey(actorId, sessionPid)),
  );
}

/** Removes all recovery material owned by an actor, for example when tenant policy disables it. */
export function clearAuthoringRecoveriesForActor(actorId: string): void {
  const prefixes = [STORAGE_PREFIX, LEGACY_STORAGE_PREFIX].flatMap((prefix) => [
    `${prefix}:inline:${encodeURIComponent(actorId)}:`,
    `${prefix}:studio:${encodeURIComponent(actorId)}:`,
  ]);
  forEachRecoveryStorage((storage) => {
    prefixes
      .flatMap((prefix) => listStorageKeys(storage, prefix))
      .forEach((key) => removeRecovery(storage, key));
  });
}

function inlineRecoveryPrefix(actorId: string, pagePid: string): string {
  return `${STORAGE_PREFIX}:inline:${encodeURIComponent(actorId)}:${encodeURIComponent(pagePid)}:`;
}

function inlineRecoveryKey(actorId: string, pagePid: string, sessionPid: string): string {
  return `${inlineRecoveryPrefix(actorId, pagePid)}${encodeURIComponent(sessionPid)}`;
}

function studioRecoveryKey(actorId: string, sessionPid: string): string {
  return `${STORAGE_PREFIX}:studio:${encodeURIComponent(actorId)}:${encodeURIComponent(sessionPid)}`;
}

function writeRecovery(
  key: string,
  recovery: InlineAuthoringRecovery | StudioAuthoringRecovery,
  policy: AuthoringRecoveryPolicy,
): boolean {
  const storage = recoveryStorage(policy);
  if (!storage) return false;
  try {
    storage.setItem(key, JSON.stringify(recovery));
    return true;
  } catch {
    // Storage can be unavailable or full. The in-memory dirty state remains authoritative locally.
    return false;
  }
}

function readRecovery(storage: Storage, key: string): unknown {
  try {
    const raw = storage.getItem(key);
    if (!raw) return null;
    const value = JSON.parse(raw) as unknown;
    if (!hasFreshTimestamp(value)) {
      storage.removeItem(key);
      return null;
    }
    return value;
  } catch {
    removeRecovery(storage, key);
    return null;
  }
}

function recoveryStorage(policy: AuthoringRecoveryPolicy): Storage | null {
  if (typeof window === 'undefined' || policy === 'DISABLED') return null;
  try {
    return policy === 'SESSION_ONLY' ? window.sessionStorage : window.localStorage;
  } catch {
    return null;
  }
}

function forEachRecoveryStorage(visit: (storage: Storage) => void): void {
  if (typeof window === 'undefined') return;
  for (const storageName of ['localStorage', 'sessionStorage'] as const) {
    try {
      visit(window[storageName]);
    } catch {
      // Best-effort cleanup only.
    }
  }
}

function listStorageKeys(storage: Storage, prefix: string): string[] {
  try {
    return Array.from({ length: storage.length }, (_, index) => storage.key(index)).filter(
      (key): key is string => Boolean(key?.startsWith(prefix)),
    );
  } catch {
    return [];
  }
}

function removeRecovery(storage: Storage, key: string): void {
  try {
    storage.removeItem(key);
  } catch {
    // Best-effort cleanup only.
  }
}

function hasFreshTimestamp(value: unknown): value is Record<string, unknown> {
  if (!isRecord(value) || typeof value.savedAt !== 'number' || !Number.isFinite(value.savedAt)) {
    return false;
  }
  const age = Date.now() - value.savedAt;
  return age >= -MAX_CLOCK_SKEW_MS && age <= RECOVERY_MAX_AGE_MS;
}

function isInlineRecovery(value: unknown): value is InlineAuthoringRecovery {
  return (
    isRecoveryBase(value, 'INLINE') &&
    Array.isArray(value.edits) &&
    value.edits.length > 0 &&
    value.edits.every(isPendingEdit)
  );
}

function isStudioRecovery(value: unknown): value is StudioAuthoringRecovery {
  return (
    isRecoveryBase(value, 'STUDIO') &&
    isRecord(value.baseSnapshot) &&
    isRecord(value.mineDocument) &&
    value.mineDocument.schemaVersion === 3 &&
    Array.isArray(value.mineDocument.blocks)
  );
}

function isRecoveryBase(
  value: unknown,
  kind: InlineAuthoringRecovery['kind'] | StudioAuthoringRecovery['kind'],
): value is AuthoringRecoveryBase & { kind: typeof kind } & Record<string, unknown> {
  return (
    hasFreshTimestamp(value) &&
    value.version === 2 &&
    value.kind === kind &&
    typeof value.actorId === 'string' &&
    typeof value.sessionPid === 'string' &&
    typeof value.pagePid === 'string' &&
    typeof value.baseRevision === 'number' &&
    Number.isInteger(value.baseRevision) &&
    (value.state === 'DIRTY' || value.state === 'UNKNOWN_OUTCOME')
  );
}

function isPendingEdit(value: unknown): value is PendingAuthoringEdit {
  return (
    isRecord(value) &&
    typeof value.key === 'string' &&
    typeof value.baseRevision === 'number' &&
    typeof value.blockId === 'string' &&
    typeof value.blockLabel === 'string' &&
    typeof value.manifestChecksum === 'string' &&
    isRecord(value.property) &&
    typeof value.property.propertyPath === 'string' &&
    (value.operation === 'ADD' || value.operation === 'REPLACE' || value.operation === 'REMOVE')
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value));
}
