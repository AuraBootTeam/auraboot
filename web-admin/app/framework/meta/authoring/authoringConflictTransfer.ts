const STORAGE_PREFIX = 'auraboot.authoring.conflict.';
const TRANSFER_TTL_MS = 10 * 60 * 1000;

export interface AuthoringConflictTransfer {
  version: 1;
  createdAt: number;
  sessionPid: string;
  changeSetPid: string;
  pagePid: string;
  baseRevision: number;
  baseSnapshot: Record<string, unknown>;
  mineSnapshot: Record<string, unknown>;
}

export function storeAuthoringConflictTransfer(
  input: Omit<AuthoringConflictTransfer, 'version' | 'createdAt'>,
): string {
  const contextId = randomContextId();
  const transfer: AuthoringConflictTransfer = {
    ...input,
    version: 1,
    createdAt: Date.now(),
  };
  window.sessionStorage.setItem(`${STORAGE_PREFIX}${contextId}`, JSON.stringify(transfer));
  return contextId;
}

export function consumeAuthoringConflictTransfer(
  contextId: string,
  expected: { sessionPid: string; changeSetPid: string; pagePid: string },
): AuthoringConflictTransfer {
  if (!/^[a-f0-9]{32}$/.test(contextId)) throw new Error('三方冲突上下文无效');
  const key = `${STORAGE_PREFIX}${contextId}`;
  const raw = window.sessionStorage.getItem(key);
  window.sessionStorage.removeItem(key);
  if (!raw) throw new Error('三方冲突上下文已使用、已过期或不属于当前标签页');

  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new Error('三方冲突上下文损坏');
  }
  if (!isConflictTransfer(value)) throw new Error('三方冲突上下文格式无效');
  if (Date.now() - value.createdAt > TRANSFER_TTL_MS || value.createdAt > Date.now() + 30_000) {
    throw new Error('三方冲突上下文已过期');
  }
  if (
    value.sessionPid !== expected.sessionPid ||
    value.changeSetPid !== expected.changeSetPid ||
    value.pagePid !== expected.pagePid
  ) {
    throw new Error('三方冲突上下文与当前会话不匹配');
  }
  return value;
}

function isConflictTransfer(value: unknown): value is AuthoringConflictTransfer {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const transfer = value as Partial<AuthoringConflictTransfer>;
  return Boolean(
    transfer.version === 1 &&
    typeof transfer.createdAt === 'number' &&
    typeof transfer.sessionPid === 'string' &&
    typeof transfer.changeSetPid === 'string' &&
    typeof transfer.pagePid === 'string' &&
    typeof transfer.baseRevision === 'number' &&
    isRecord(transfer.baseSnapshot) &&
    isRecord(transfer.mineSnapshot),
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value));
}

function randomContextId(): string {
  const bytes = new Uint8Array(16);
  window.crypto.getRandomValues(bytes);
  return [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('');
}
