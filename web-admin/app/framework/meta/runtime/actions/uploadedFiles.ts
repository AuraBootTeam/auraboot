import type { UploadedFilesInputConfig } from '../../schemas/types';

export const publicFilePid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(value);

export function uploadedFilesConfig(value: unknown): Required<UploadedFilesInputConfig> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid uploaded-files configuration');
  const config = value as Record<string, unknown>;
  if (
    Object.keys(config).some((key) => !['maxCount', 'accept', 'maxBytes'].includes(key)) ||
    !Number.isInteger(config.maxCount) ||
    Number(config.maxCount) < 1 ||
    Number(config.maxCount) > 10 ||
    typeof config.accept !== 'string' ||
    !config.accept.trim() ||
    config.accept
      .split(',')
      .some((part) => !/^(?:\.[a-z0-9]+|[a-z0-9.+-]+\/(?:[a-z0-9.+-]+|\*))$/i.test(part.trim()))
  )
    throw new Error('Invalid uploaded-files configuration');
  const maxBytes = config.maxBytes ?? 10 * 1024 * 1024;
  if (!Number.isSafeInteger(maxBytes) || Number(maxBytes) < 1)
    throw new Error('Invalid uploaded-files byte limit');
  return { maxCount: Number(config.maxCount), accept: config.accept, maxBytes: Number(maxBytes) };
}

export function validUploadedFilePids(value: unknown, maxCount: number): value is string[] {
  return (
    Array.isArray(value) &&
    value.length <= maxCount &&
    value.every(publicFilePid) &&
    new Set(value).size === value.length
  );
}
