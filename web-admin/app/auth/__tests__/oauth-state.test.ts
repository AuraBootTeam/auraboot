import { describe, expect, it } from 'vitest';

import {
  consumeOAuthState,
  linkOAuthStateKey,
  loginOAuthStateKey,
} from '../oauth-state';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length() {
    return this.values.size;
  }

  clear() {
    this.values.clear();
  }

  getItem(key: string) {
    return this.values.get(key) ?? null;
  }

  key(index: number) {
    return [...this.values.keys()][index] ?? null;
  }

  removeItem(key: string) {
    this.values.delete(key);
  }

  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
}

describe('OAuth browser state correlation', () => {
  it('uses operation- and provider-specific storage keys', () => {
    expect(loginOAuthStateKey('Company-OIDC')).toBe(
      'auth.oauth.login.state.company-oidc',
    );
    expect(linkOAuthStateKey('Company-OIDC')).toBe(
      'auth.oauth.link.state.company-oidc',
    );
  });

  it('accepts a matching state once and consumes it', () => {
    const storage = new MemoryStorage();
    storage.setItem('state-key', 'expected');

    expect(consumeOAuthState(storage, 'state-key', 'expected')).toBe(true);
    expect(consumeOAuthState(storage, 'state-key', 'expected')).toBe(false);
  });

  it('consumes the pending state even when the callback is missing or mismatched', () => {
    const storage = new MemoryStorage();
    storage.setItem('state-key', 'expected');

    expect(consumeOAuthState(storage, 'state-key', 'attacker')).toBe(false);
    expect(storage.getItem('state-key')).toBeNull();

    storage.setItem('state-key', 'expected-again');
    expect(consumeOAuthState(storage, 'state-key', null)).toBe(false);
    expect(storage.getItem('state-key')).toBeNull();
  });
});
