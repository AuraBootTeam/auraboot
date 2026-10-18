export type PublicRecordLike = Record<string, unknown> | null | undefined;

export function toPublicRecordPid(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  const text = String(value).trim();
  return text.length > 0 ? text : undefined;
}

export function getPublicRecordPid(record: PublicRecordLike): string | undefined {
  return toPublicRecordPid(record?.pid);
}

export function getLegacyCompatibleRecordPid(record: PublicRecordLike): string | undefined {
  return getPublicRecordPid(record);
}

export function getPublicRecordKey(
  record: PublicRecordLike,
  fallback?: string | number,
  preferredField?: string,
): string | undefined {
  const preferred = preferredField ? toPublicRecordPid(record?.[preferredField]) : undefined;
  return preferred ?? getPublicRecordPid(record);
}

export function buildCommandTargetParams(target: unknown): Record<string, string> {
  const pid = toPublicRecordPid(target);
  return pid ? { targetRecordPid: pid } : {};
}

/** Read a public PID from synchronous or nested command results. */
export function getCommandResultRecordPid(value: unknown): string | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const record = value as Record<string, unknown>;
  const pid = record.recordPid || record.pid;
  return typeof pid === 'string' ? pid : getCommandResultRecordPid(record.data);
}
