import { describe, expect, it } from 'vitest';

import { getLoginFailureActionData } from '../login-errors';

describe('getLoginFailureActionData', () => {
  it('maps invalid credential query errors to the existing login action error shape', () => {
    const actionData = getLoginFailureActionData(
      new URLSearchParams('error=invalidCredentials&channelCode=email_password'),
    );

    expect(actionData).toEqual({
      channelCode: 'email_password',
      errors: { general: 'auth.error.invalidCredentials' },
    });
  });

  it('preserves the login channel so failed SMS and email-code submissions keep the active tab', () => {
    expect(getLoginFailureActionData(new URLSearchParams('error=invalidCredentials&channelCode=sms'))).toEqual({
      channelCode: 'sms',
      errors: { general: 'auth.error.invalidCredentials' },
    });
  });

  it('returns undefined when no login error is present', () => {
    expect(getLoginFailureActionData(new URLSearchParams('redirectTo=%2F'))).toBeUndefined();
  });
});
