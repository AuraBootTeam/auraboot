import type { RequestHandler } from 'express';
import {
  getTokenFromRequest,
  getImpersonationFromRequest,
  maybeRenewSession,
  sessionStorage,
} from '~/shared/services/session';
import { processMenuData, transformMenuForUI } from '~/shared/services/menu';
import {
  SESSION_CONTEXT_UNAVAILABLE,
  validateSessionContext,
} from '~/shared/services/session-context';
import {
  createLoaderDiagnostics,
  isRootDependencyUnavailable,
} from '../observability/loaderDiagnostics.server';

export const SESSION_CONTEXT_DEADLINE_MS = 8_000;

/** Session maintenance never resolves branding, bootstrap state, spaces or business loaders. */
export function createSessionContextHandler(backendUrl: string): RequestHandler {
  return async (req, res, next) => {
    const controller = new AbortController();
    const deadline = setTimeout(
      () => controller.abort(new DOMException('Session context deadline exceeded', 'TimeoutError')),
      SESSION_CONTEXT_DEADLINE_MS,
    );
    const cancel = () => {
      if (!res.writableEnded) controller.abort();
    };
    res.once('close', cancel);
    const headers = new Headers({ Cookie: req.headers.cookie || '' });
    for (const name of ['x-request-id', 'traceparent']) {
      const value = req.headers[name];
      if (typeof value === 'string') headers.set(name, value);
    }
    const request = new Request('http://bff.local/api/runtime/session-context', {
      headers,
      signal: controller.signal,
    });
    const diagnostics = createLoaderDiagnostics(request, 'session.context');
    res.set('Cache-Control', 'no-store');
    const reject = async (status: number) => {
      if (status === 401) {
        const session = await sessionStorage.getSession(req.headers.cookie || '');
        res.setHeader('Set-Cookie', await sessionStorage.destroySession(session));
      }
      res.status(status).json({ code: status === 401 ? 'session_expired' : 'session_forbidden' });
    };
    const read = async (path: string, token: string) => {
      const response = await fetch(`${backendUrl}${path}`, {
        headers: {
          Authorization: `Bearer ${token}`,
          'X-Request-Id': diagnostics.correlation.requestId,
        },
        signal: controller.signal,
      });
      if (!response.ok) throw response;
      const body = await response.json();
      if (String(body?.code) !== '0' || body.data == null)
        throw new Error('Session dependency returned an invalid contract');
      return body.data;
    };
    try {
      await diagnostics.stage('request', async () => {
        const token = await getTokenFromRequest(request);
        if (!token) {
          await reject(401);
          return;
        }
        if (await getImpersonationFromRequest(request)) {
          res.status(409).json({ code: 'session_impersonation_active' });
          return;
        }
        const userInfo = await diagnostics.stage('identity', () => read('/api/auth/me', token));
        if (!userInfo.user) throw new Error('Session context has no authenticated user');
        // The same public user projection as root-loader data; JWTs stay in the signed cookie.
        const { jwt: _jwt, ...user } = userInfo.user;
        const menus = await diagnostics.stage('menus', () => read('/api/menu/user', token));
        if (!Array.isArray(menus)) throw new Error('Session menu response must be an array');
        const snapshot = validateSessionContext({
          user,
          permissions: userInfo.permissions ?? { roles: [], permissions: [] },
          preferences: userInfo.preferences ?? null,
          menus: transformMenuForUI(processMenuData(menus)),
        });
        const renewal = await diagnostics.stage('renewal', () => maybeRenewSession(request));
        controller.signal.throwIfAborted();
        if (renewal.setCookie) res.setHeader('Set-Cookie', renewal.setCookie);
        res.json({ code: '0', data: snapshot, renewed: renewal.renewed });
      });
    } catch (error) {
      if (res.destroyed) return;
      if (error instanceof Response && [401, 403].includes(error.status)) {
        await reject(error.status);
        return;
      }
      if (
        isRootDependencyUnavailable(error) ||
        (error instanceof Response && [502, 503, 504].includes(error.status))
      ) {
        res
          .status(503)
          .json({
            code: SESSION_CONTEXT_UNAVAILABLE,
            requestId: diagnostics.correlation.requestId,
            traceId: diagnostics.correlation.traceId,
            stage: diagnostics.failedStage,
          });
      } else next(error);
    } finally {
      clearTimeout(deadline);
      res.off('close', cancel);
    }
  };
}
