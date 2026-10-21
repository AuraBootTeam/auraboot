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

/** Resolve a saved record from a command result envelope. */
export function getCommandResultRecordPid(value: unknown): string | undefined {
  let current = value;
  const seen = new Set<object>();
  while (current && typeof current === 'object' && !Array.isArray(current)) {
    if (seen.has(current)) return undefined;
    seen.add(current);
    const record = current as Record<string, unknown>;
    for (const candidate of [record.recordPid, record.pid]) {
      if (typeof candidate === 'string' && candidate.trim()) return candidate.trim();
    }
    current = record.data;
  }
  return undefined;
}
