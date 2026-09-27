import { afterEach, describe, expect, it, vi } from 'vitest';
import { createBrowserContext } from '../RequestContext';

describe('browser request context', () => {
  afterEach(() => vi.restoreAllMocks());

  it('never reads the JWT from browser storage', () => {
    window.sessionStorage.setItem('jwtToken', 'session-token');
    window.localStorage.setItem('jwtToken', 'local-token');
    const sessionGet = vi.spyOn(Storage.prototype, 'getItem');

    const context = createBrowserContext();

    expect(context.token).toBeUndefined();
    expect(sessionGet).not.toHaveBeenCalledWith('jwtToken');
  });
});
