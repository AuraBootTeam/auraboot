export interface DetailModalTarget {
  path: string;
  tableName: string;
  recordPid: string;
  width: number;
}

/** Restrict modal presentation to the existing ordinary detail route. */
export function resolveDetailModalTarget(path: string, width: unknown = 900): DetailModalTarget {
  if (typeof width !== 'number' || !Number.isFinite(width) || width <= 0) {
    throw new Error('Detail modal width must be a positive number');
  }
  const match = /^\/p\/([^/?#]+)\/view\/([^/?#]+)$/.exec(path);
  if (!match) throw new Error('Modal presentation requires an internal detail target');
  const tableName = decodeURIComponent(match[1]);
  const recordPid = decodeURIComponent(match[2]);
  if (
    !/^[A-Za-z0-9_-]+$/.test(tableName) ||
    !recordPid.trim() ||
    ['undefined', 'null', '.', '..'].includes(recordPid) ||
    /[\/\\?#\u0000-\u001f]/.test(recordPid)
  ) {
    throw new Error('Detail modal requires a model and public record PID');
  }
  const url = new URL(path, 'http://detail-modal.invalid');
  url.hash = '';
  url.searchParams.set('presentation', 'modal');
  return { path: `${url.pathname}${url.search}`, tableName, recordPid, width };
}

/** Restored modal has no live source DMS callback; load the real source page. */
export function reloadDetailModalSource(source: string): void {
  if (!source.startsWith('/') || source.startsWith('//')) throw new Error('Invalid source route');
  window.location.replace(source);
}
