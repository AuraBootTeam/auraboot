const LOGIN_FAILURE_MESSAGES: Record<string, string> = {
  invalidCredentials: 'auth.error.invalidCredentials',
  missingToken: 'Missing token',
  unsupportedLoginMethod: 'Unsupported login method',
};

const LOGIN_FORM_CHANNELS = new Set(['email_password', 'sms', 'email_code']);

export interface LoginFailureActionData {
  channelCode?: string;
  errors: {
    general: string;
  };
}

export function getLoginFailureActionData(searchParams: URLSearchParams): LoginFailureActionData | undefined {
  const error = searchParams.get('error')?.trim();
  if (!error) return undefined;

  const channelCode = searchParams.get('channelCode')?.trim().toLowerCase();
  return {
    ...(channelCode && LOGIN_FORM_CHANNELS.has(channelCode) ? { channelCode } : {}),
    errors: {
      general: LOGIN_FAILURE_MESSAGES[error] || error,
    },
  };
}
