/**
 * AuraBotChat-error-i18n.test.tsx
 *
 * Pins the i18n behaviour of the AuraBot error bubble.
 *
 * The backend streams user-facing errors as a `$i18n:<key>` sentinel (the
 * service layer has no request locale), and the chat panel resolves it via
 * {@code useSmartText} where the browser locale is known. Regression guard for
 * the "No LLM provider configured…" bug where the raw English string leaked to
 * a Chinese user.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code resolvesSentinelToLocalizedText} — a `$i18n:` error content is
 *       rendered as the localized catalog value, and the raw `$i18n:` sentinel
 *       never leaks to the DOM.</li>
 *   <li>{@code plainErrorPassesThrough} — a non-sentinel error string (e.g. a
 *       raw exception message) is rendered unchanged.</li>
 * </ul>
 */

import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';

// Resolve the i18n context so useSmartText translates the aurabot error key.
// The factory is hoisted above imports, so the catalog map is declared inline.
vi.mock('~/contexts/I18nContext', async (importActual) => {
  const actual = await importActual<Record<string, unknown>>();
  const CATALOG: Record<string, string> = {
    'aurabot.error.no_llm_provider': '未配置 LLM 服务商，请在云配置中添加 API Key。',
  };
  return {
    ...actual,
    useI18n: () => ({
      t: (key: string, _params?: Record<string, unknown>, fallback?: string) =>
        CATALOG[key] ?? fallback ?? key,
      locale: 'zh-CN',
      setLocale: () => {},
      loading: false,
      recovering: false,
      isRTL: false,
    }),
  };
});

import { AuraBotChat } from '../AuraBotChat';
import { AuraBotCtx } from '../../components-shell/AuraBotProvider';

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

function renderChatWithError(content: string) {
  const ctx: any = {
    state: {
      panelState: 'open',
      sessionId: 'test-session',
      currentConversationId: null,
      messages: [
        { id: 'err-1', type: 'error', sender: 'bot', timestamp: 0, content },
      ],
      isLoading: false,
      pageContext: {},
      inputValue: '',
      selectedAgentCode: 'default',
      selectedKnowledgeBaseIds: [],
      knowledgeBases: [],
    },
    sessions: [],
    openPanel: vi.fn(),
    closePanel: vi.fn(),
    togglePanel: vi.fn(),
    sendMessage: vi.fn(),
    confirmTool: vi.fn(),
    cancelTool: vi.fn(),
    clearMessages: vi.fn(),
    newSession: vi.fn(),
    selectSession: vi.fn(),
    deleteSession: vi.fn(),
    setInputValue: vi.fn(),
    setPageContext: vi.fn(),
    setSelectedAgent: vi.fn(),
    toggleKnowledgeBase: vi.fn(),
    registerFormFillHandler: vi.fn(),
    unregisterFormFillHandler: vi.fn(),
  };

  render(
    <AuraBotCtx.Provider value={ctx}>
      <AuraBotChat />
    </AuraBotCtx.Provider>,
  );
}

describe('AuraBotChat — error message i18n', () => {
  it('resolvesSentinelToLocalizedText — $i18n sentinel renders as localized text, no raw code leak', () => {
    renderChatWithError('$i18n:aurabot.error.no_llm_provider');

    expect(
      screen.getByText('未配置 LLM 服务商，请在云配置中添加 API Key。'),
    ).toBeInTheDocument();
    // The raw sentinel must never reach the DOM.
    expect(screen.queryByText(/\$i18n:/)).not.toBeInTheDocument();
  });

  it('plainErrorPassesThrough — a non-sentinel error string is rendered unchanged', () => {
    renderChatWithError('LLM request failed: connection timeout');

    expect(
      screen.getByText('LLM request failed: connection timeout'),
    ).toBeInTheDocument();
  });
});
