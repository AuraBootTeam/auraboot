/**
 * D.1 (2026-05-07): unit-test the AuraBotConversationMessage → SimpleMessage[]
 * mapping. Ensures persisted Anthropic Extended Thinking prose hydrates back
 * into a {@code type: 'thinking'} pane on history reload — same shape the
 * live SSE thinking event creates via the provider's reducer.
 */
import { describe, expect, it } from 'vitest';

import { toSimpleMessages } from '../AuraBotProvider';
import type { AuraBotConversationMessage } from '../../services/auraBotApi';

const ASSISTANT_BASE: AuraBotConversationMessage = {
  id: 42,
  conversationId: 7,
  seq: 1,
  sender: 'assistant',
  type: 'ai_response',
  content: 'Final answer.',
  createdAt: '2026-05-07T10:00:00Z',
};

describe('toSimpleMessages — D.1 thinking history hydration', () => {
  it('emits both a thinking message and the answer text when thinkingContent is present', () => {
    const out = toSimpleMessages({
      ...ASSISTANT_BASE,
      thinkingContent: 'Step 1: read input. Step 2: produce answer.',
      thinkingSignature: 'sig-XYZ',
    });

    expect(out).toHaveLength(2);
    const [thinking, answer] = out;
    expect(thinking.type).toBe('thinking');
    expect(thinking.sender).toBe('bot');
    expect(thinking.content).toBe('Step 1: read input. Step 2: produce answer.');
    expect(thinking.thinkingSignature).toBe('sig-XYZ');
    // Render order: thinking before answer (1ms earlier so list-sort by ts is stable).
    expect(thinking.timestamp).toBeLessThan(answer.timestamp);
    expect(answer.type).toBe('text');
    expect(answer.content).toBe('Final answer.');
  });

  it('omits the thinking pane when thinkingContent is null/undefined (legacy rows)', () => {
    const out = toSimpleMessages({
      ...ASSISTANT_BASE,
      thinkingContent: null,
      thinkingSignature: null,
    });
    expect(out).toHaveLength(1);
    expect(out[0].type).toBe('text');
  });

  it('treats empty-string thinkingContent the same as missing (defensive)', () => {
    const out = toSimpleMessages({
      ...ASSISTANT_BASE,
      thinkingContent: '',
    });
    expect(out).toHaveLength(1);
    expect(out[0].type).toBe('text');
  });

  it('never attaches a thinking pane to user / system rows even if column drift surfaces a value', () => {
    const userRow: AuraBotConversationMessage = {
      ...ASSISTANT_BASE,
      sender: 'user',
      type: 'text',
      content: 'My question',
      thinkingContent: 'leaked content',
    };
    const out = toSimpleMessages(userRow);
    expect(out).toHaveLength(1);
    expect(out[0].type).toBe('text');
    expect(out[0].sender).toBe('user');
  });
});
