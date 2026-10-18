import type { RequestHandler } from 'express';
import axios from 'axios';
import { resolveDeploymentBranding, type BrandingEnvironment } from '~/config/branding.server';
import { resolveAuthAppearance } from '~/config/auth-appearance';
import { createLoaderDiagnostics } from '../observability/loaderDiagnostics.server';

// Finish the inner read before the root loader's ten-second deadline.
export const AUTH_APPEARANCE_TIMEOUT_MS = 8_000;

export function createRuntimeBrandingHandler(
  backendUrl: string,
  environment: BrandingEnvironment = process.env,
): RequestHandler {
  return async (req, res, next) => {
    const headers = new Headers();
    for (const name of ['x-request-id', 'traceparent']) {
      const value = req.headers[name];
      if (typeof value === 'string') headers.set(name, value);
    }
    const diagnostics = createLoaderDiagnostics(
      new Request('http://bff.local/api/runtime/branding', { headers }),
      'runtime.branding',
    );
    const controller = new AbortController();
    const cancel = () => {
      if (!res.writableEnded) controller.abort();
    };
    res.once('close', cancel);
    try {
      await diagnostics.stage('request', async () => {
        const branding = await diagnostics.stage('deployment-config', () =>
          resolveDeploymentBranding(environment),
        );
        if (branding.mode === 'commercial' && req.query.include === 'auth-appearance') {
          const response = await diagnostics.stage('appearance-fetch', () =>
            axios.get(`${backendUrl}/api/auth/appearance`, {
              timeout: AUTH_APPEARANCE_TIMEOUT_MS,
              signal: controller.signal,
              headers: { 'X-Request-Id': diagnostics.correlation.requestId },
            }),
          );
          await diagnostics.stage('appearance-validation', async () => {
            if (String(response.data?.code) !== '0' || !response.data?.data) {
              throw new Error('Unable to resolve published authentication appearance');
            }
            if (response.data.data.appearance !== null) {
              branding.authAppearance = resolveAuthAppearance(response.data.data.appearance);
            }
          });
        }
        await diagnostics.stage('serialize', async () => {
          res.set('Cache-Control', 'no-store');
          res.json({ branding });
        });
      });
    } catch (error) {
      if (controller.signal.aborted) return;
      if (
        axios.isAxiosError(error) &&
        ([502, 503, 504].includes(error.response?.status || 0) ||
          (!error.response &&
            ['ECONNRESET', 'ECONNREFUSED', 'ECONNABORTED', 'ETIMEDOUT', 'EAI_AGAIN'].includes(
              error.code || '',
            )))
      ) {
        const unavailable = new Error(
          'Published authentication appearance is temporarily unavailable',
          { cause: error },
        );
        Object.assign(unavailable, { status: 503, code: 'root_dependency_unavailable' });
        next(unavailable);
      } else next(error);
    } finally {
      res.off('close', cancel);
    }
  };
}
