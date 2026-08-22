export const loginOAuthStateKey = (provider: string) =>
  `auth.oauth.login.state.${provider.toLowerCase()}`;

export const LINK_OAUTH_PROVIDER_KEY = 'auth.oauth.link.provider';

export const linkOAuthStateKey = (provider: string) =>
  `auth.oauth.link.state.${provider.toLowerCase()}`;

/** Consume browser-side OAuth correlation state exactly once, including on mismatch. */
export function consumeOAuthState(storage: Storage, key: string, returnedState: string | null) {
  const expectedState = storage.getItem(key);
  storage.removeItem(key);
  return Boolean(expectedState && returnedState && expectedState === returnedState);
}
