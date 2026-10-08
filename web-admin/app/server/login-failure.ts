export type LoginFailureCode = 'invalidCredentials' | 'missingToken' | 'unsupportedLoginMethod';

export function buildLoginFailureRedirect(
  redirectTo: string,
  options: {
    error: LoginFailureCode;
    channelCode?: string | null;
    loginPath?: '/login' | '/admin-login';
  },
) {
  const params = new URLSearchParams();
  params.set('redirectTo', redirectTo || '/');
  params.set('error', options.error);

  const channelCode = options.channelCode?.trim().toLowerCase();
  if (channelCode) {
    params.set('channelCode', channelCode);
  }

  return `${options.loginPath === '/admin-login' ? '/admin-login' : '/login'}?${params.toString()}`;
}
