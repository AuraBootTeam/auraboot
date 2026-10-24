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
    'aurabot.chat.input.placeholder': '输入消息…（按 Enter 发送）',
    'aurabot.chat.input.keyboard_hint': 'Cmd+J 打开｜Enter 发送｜Shift+Enter 换行',
    'aurabot.chat.attach_image': '添加图片',
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

function renderChatWithError(content: string, type: 'error' | 'text' | 'tool_cancelled' = 'error', overrides: Record<string, unknown> = {}) {
  const ctx: any = {
    state: {
      panelState: 'open',
      sessionId: 'test-session',
      currentConversationId: null,
      messages: [
        { id: 'err-1', type, sender: 'bot', timestamp: 0, content, ...overrides },
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
  it('localizes the visible composer controls under zh-CN', () => {
    renderChatWithError('测试消息');

    expect(screen.getByPlaceholderText('输入消息…（按 Enter 发送）')).toBeInTheDocument();
    expect(screen.getByText('Cmd+J 打开｜Enter 发送｜Shift+Enter 换行')).toBeInTheDocument();
    expect(screen.getByTitle('添加图片')).toBeInTheDocument();
    expect(screen.queryByText(/Enter send/)).not.toBeInTheDocument();
  });

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


it('localizes the fixed approved-tool acknowledgement in persisted text messages', () => {
  renderChatWithError('Approved tool executed.', 'text');
  expect(screen.getByTestId('chat-msg-agent')).toHaveTextContent('审批通过，操作已完成。');
  expect(screen.getByTestId('chat-msg-agent')).not.toHaveTextContent('Approved tool executed.');
});


it('does not render an empty assistant text placeholder', () => {
  renderChatWithError('   ', 'text');
  expect(screen.queryByTestId('chat-msg-agent')).not.toBeInTheDocument();
});

it('announces a completed cancellation as readable status feedback', () => {
  renderChatWithError('操作已取消', 'tool_cancelled');
  expect(screen.getByRole('status')).toHaveTextContent('操作已取消');
});


describe('AuraBotChat — assistant Markdown and trusted source references', () => {
  it('renders headings and GFM table cells instead of formatting markers', () => {
    renderChatWithError('## Maintenance\n\n| Item | Value |\n| --- | ---: |\n| Cooling | **43 minutes** |', 'text');
    expect(screen.getByRole('heading', { name: 'Maintenance' })).toBeInTheDocument();
    expect(screen.getByRole('table')).toHaveTextContent('43 minutes');
    expect(screen.getByText('43 minutes').tagName).toBe('STRONG');
  });

  it('presents only matching trusted evidence references with readable document names', () => {
    renderChatWithError('Source: `chunk:trusted`; unknown: `chunk:unknown`', 'text', { retrievalEvidence: [{
      evidenceId: 'chunk:trusted', chunkPid: 'trusted', chunkIndex: 0, documentName: 'policy.txt',
      path: 'hybrid', vectorScore: 0.7, warnings: [],
    }] });
    const reply = screen.getByTestId('chat-msg-agent');
    expect(reply).toHaveTextContent('policy.txt');
    expect(reply).not.toHaveTextContent('chunk:trusted');
    expect(reply).toHaveTextContent('chunk:unknown');
  });

  it('does not execute raw HTML or allow javascript links in generated Markdown', () => {
    renderChatWithError('<script>window.stolen=true</script>\n\n[unsafe](javascript:alert(1))', 'text');
    const reply = screen.getByTestId('chat-msg-agent');
    expect(reply.querySelector('script')).toBeNull();
    expect(reply.querySelector('a[href^="javascript:"]')).toBeNull();
    expect(reply.textContent).not.toContain('<script>');
  });

  it('keeps human Markdown literal', () => {
    renderChatWithError('## Literal human **text**', 'text', { sender: 'user' });
    expect(screen.getByTestId('chat-msg-user')).toHaveTextContent('## Literal human **text**');
    expect(screen.queryByRole('heading', { name: /Literal human/ })).not.toBeInTheDocument();
  });
});
