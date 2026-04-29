import { describe, expect, it } from 'vitest';

import { shouldAutoHydrateConversation } from '../AuraBotProvider';

describe('shouldAutoHydrateConversation', () => {
  it('keeps stale refresh responses from hydrating after the user starts a new session', () => {
    expect(
      shouldAutoHydrateConversation(
        {
          sessionId: 'session-after-agent-select',
          currentConversationId: null,
          messages: [],
        },
        'session-before-agent-select',
      ),
    ).toBe(false);
  });

  it('allows auto-hydration only while the original empty session is still current', () => {
    expect(
      shouldAutoHydrateConversation(
        {
          sessionId: 'session-before-refresh',
          currentConversationId: null,
          messages: [],
        },
        'session-before-refresh',
      ),
    ).toBe(true);

    expect(
      shouldAutoHydrateConversation(
        {
          sessionId: 'session-before-refresh',
          currentConversationId: null,
          messages: [{ id: 'user-message' }],
        },
        'session-before-refresh',
      ),
    ).toBe(false);
  });
});
