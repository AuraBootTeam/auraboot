import { EventEmitter } from 'node:events';
import { afterEach, describe, expect, it, vi } from 'vitest';
import logger from '../logger';
import { requestLogger } from '../../middlewares/RequestLogger';
afterEach(() => vi.restoreAllMocks());
describe('public-link request logging', () => {
  it.each([200, 422, 500])(
    'redacts start and %s completion while retaining the original route',
    (status) => {
      const path =
        '/api/views/shared/private-link-fixture?password=private-password-fixture&mode=read';
      const request = { originalUrl: path, url: path, method: 'GET', headers: {}, socket: {} };
      const response = Object.assign(new EventEmitter(), {
        setHeader: vi.fn(),
        getHeader: vi.fn(),
        statusCode: status,
        writableEnded: true,
      });
      const spies = [vi.spyOn(logger, 'info'), vi.spyOn(logger, 'warn'), vi.spyOn(logger, 'error')];
      for (const spy of spies) spy.mockImplementation(() => undefined as never);
      const next = vi.fn();
      requestLogger(request as any, response as any, next);
      response.emit('finish');
      expect(next).toHaveBeenCalledOnce();
      expect(request.originalUrl).toBe(path);
      expect(request.url).toBe(path);
      const diagnostics = JSON.stringify(spies.flatMap((spy) => spy.mock.calls));
      expect(diagnostics).not.toContain('private-link-fixture');
      expect(diagnostics).not.toContain('private-password-fixture');
      expect(diagnostics).toContain('mode=read');
      expect(diagnostics).toContain('[REDACTED]');
    },
  );
});
