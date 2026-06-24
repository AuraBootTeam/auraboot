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
