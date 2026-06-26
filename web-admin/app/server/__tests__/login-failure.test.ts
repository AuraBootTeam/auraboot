import { describe, expect, it } from 'vitest';

import { buildLoginFailureRedirect } from '../login-failure';

describe('buildLoginFailureRedirect', () => {
  it('redirects failed browser login posts back to the login page with structured error state', () => {
    const location = buildLoginFailureRedirect('/p/bom_material_sync_state?from=menu', {
      channelCode: 'email_password',
      error: 'invalidCredentials',
    });
    const url = new URL(location, 'https://example.test');

    expect(url.pathname).toBe('/login');
    expect(url.searchParams.get('redirectTo')).toBe('/p/bom_material_sync_state?from=menu');
    expect(url.searchParams.get('error')).toBe('invalidCredentials');
    expect(url.searchParams.get('channelCode')).toBe('email_password');
  });

  it('omits the channel when the failure did not come from a channel form', () => {
    const location = buildLoginFailureRedirect('/', { error: 'missingToken' });
    const url = new URL(location, 'https://example.test');

    expect(url.pathname).toBe('/login');
    expect(url.searchParams.get('redirectTo')).toBe('/');
    expect(url.searchParams.get('error')).toBe('missingToken');
    expect(url.searchParams.has('channelCode')).toBe(false);
  });
});
