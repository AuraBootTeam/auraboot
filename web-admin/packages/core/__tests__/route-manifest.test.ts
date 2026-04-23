import { describe, expect, it } from 'vitest';
import { coreRoutes } from '../route-manifest';

describe('coreRoutes', () => {
  it('registers the legacy inbox route as a static route', () => {
    const routes = coreRoutes();

    expect(routes).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: '/inbox',
          file: './routes/inbox/index.tsx',
        }),
      ]),
    );
  });
});
