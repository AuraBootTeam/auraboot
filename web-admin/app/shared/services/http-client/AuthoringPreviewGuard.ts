import type { FetchOptions, Result } from './types';

export const AUTHORING_WRITE_BLOCKED_EVENT = 'auraboot:authoring-write-blocked';

const activeSessions = new Set<string>();
const AUTHORING_PATH = '/api/authoring/';
const SAFE_QUERY_POST_PATHS = ['/api/datasource/batch', '/api/pages/batch'];

export function activateAuthoringPreviewGuard(sessionPid: string): () => void {
  activeSessions.add(sessionPid);
  return () => activeSessions.delete(sessionPid);
}

export function resetAuthoringPreviewGuardForTests(): void {
  activeSessions.clear();
}

export function authoringPreviewBlockedResult<T>(
  path: string,
  options: FetchOptions,
): Result<T> | null {
  if (activeSessions.size === 0 || isSafeRequest(path, options)) return null;

  if (typeof window !== 'undefined') {
    window.dispatchEvent(
      new CustomEvent(AUTHORING_WRITE_BLOCKED_EVENT, {
        detail: { path, method: (options.method || 'get').toLowerCase() },
      }),
    );
  }
  return {
    code: 'authoring_preview_write_blocked',
    desc: '配置模式禁止写入真实业务数据',
    message: '配置模式禁止写入真实业务数据',
    success: false,
    data: null,
    context: { path },
  };
}

function isSafeRequest(path: string, options: FetchOptions): boolean {
  const method = (options.method || 'get').toLowerCase();
  if (path.startsWith(AUTHORING_PATH)) return true;
  if (method === 'get' || method === 'options') return true;
  if (method === 'post') {
    return SAFE_QUERY_POST_PATHS.some((safePath) => path === safePath);
  }
  return false;
}
