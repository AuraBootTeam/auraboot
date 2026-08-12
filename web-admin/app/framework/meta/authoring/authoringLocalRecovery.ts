import type { PageSchemaV3 } from '~/plugins/core-designer/components/unified-designer/types';
import type { PendingAuthoringEdit } from './types';

const STORAGE_PREFIX = 'auraboot:authoring-recovery:v1';
const RECOVERY_MAX_AGE_MS = 24 * 60 * 60 * 1000;
const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000;

type RecoveryState = 'DIRTY' | 'UNKNOWN_OUTCOME';

interface AuthoringRecoveryBase {
  version: 1;
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
): boolean {
  return writeRecovery(inlineRecoveryKey(recovery.actorId, recovery.pagePid), {
    ...recovery,
    version: 1,
    kind: 'INLINE',
    savedAt: Date.now(),
  });
}

export function readInlineAuthoringRecovery(
  actorId: string,
  pagePid: string,
): InlineAuthoringRecovery | null {
  const key = inlineRecoveryKey(actorId, pagePid);
  const recovery = readRecovery(key);
  if (!isInlineRecovery(recovery) || recovery.actorId !== actorId || recovery.pagePid !== pagePid) {
    removeRecovery(key);
    return null;
  }
  return recovery;
}

export function clearInlineAuthoringRecovery(
  actorId: string,
  pagePid: string,
  expectedSessionPid?: string,
): void {
  const key = inlineRecoveryKey(actorId, pagePid);
  if (expectedSessionPid) {
    const current = readRecovery(key);
    if (isInlineRecovery(current) && current.sessionPid !== expectedSessionPid) return;
  }
  removeRecovery(key);
}

export function storeStudioAuthoringRecovery(
  recovery: Omit<StudioAuthoringRecovery, 'version' | 'kind' | 'savedAt'>,
): boolean {
  return writeRecovery(studioRecoveryKey(recovery.actorId, recovery.sessionPid), {
    ...recovery,
    version: 1,
    kind: 'STUDIO',
    savedAt: Date.now(),
  });
}

export function readStudioAuthoringRecovery(
  actorId: string,
  sessionPid: string,
): StudioAuthoringRecovery | null {
  const key = studioRecoveryKey(actorId, sessionPid);
  const recovery = readRecovery(key);
  if (
    !isStudioRecovery(recovery) ||
    recovery.actorId !== actorId ||
    recovery.sessionPid !== sessionPid
  ) {
    removeRecovery(key);
    return null;
  }
  return recovery;
}

export function clearStudioAuthoringRecovery(actorId: string, sessionPid: string): void {
  removeRecovery(studioRecoveryKey(actorId, sessionPid));
}

function inlineRecoveryKey(actorId: string, pagePid: string): string {
  return `${STORAGE_PREFIX}:inline:${encodeURIComponent(actorId)}:${encodeURIComponent(pagePid)}`;
}

function studioRecoveryKey(actorId: string, sessionPid: string): string {
  return `${STORAGE_PREFIX}:studio:${encodeURIComponent(actorId)}:${encodeURIComponent(sessionPid)}`;
}

function writeRecovery(
  key: string,
  recovery: InlineAuthoringRecovery | StudioAuthoringRecovery,
): boolean {
  if (typeof window === 'undefined') return false;
  try {
    window.sessionStorage.setItem(key, JSON.stringify(recovery));
    return true;
  } catch {
    // Storage can be unavailable or full. The in-memory dirty state remains authoritative locally.
    return false;
  }
}

function readRecovery(key: string): unknown {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.sessionStorage.getItem(key);
    if (!raw) return null;
    const value = JSON.parse(raw) as unknown;
    if (!hasFreshTimestamp(value)) {
      window.sessionStorage.removeItem(key);
      return null;
    }
    return value;
  } catch {
    removeRecovery(key);
    return null;
  }
}

function removeRecovery(key: string): void {
  if (typeof window === 'undefined') return;
  try {
    window.sessionStorage.removeItem(key);
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
    value.version === 1 &&
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
