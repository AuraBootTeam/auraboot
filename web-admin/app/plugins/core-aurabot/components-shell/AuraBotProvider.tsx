/**
 * AuraBotProvider
 *
 * Global context provider for AuraBot AI assistant.
 * Manages panel state (collapsed/expanded), messages, page context, and SSE chat.
 *
 * @since 2.0.0
 */

import React, {
  createContext,
  useContext,
  useReducer,
  useCallback,
  useEffect,
  useRef,
} from 'react';
import { useLocation, useParams } from 'react-router';
import type { PageContext } from '../hooks/usePageContext';
import {
  auraBotApi,
  type AuraBotConversationItem,
  type AuraBotConversationMessage,
} from '../services/auraBotApi';

// ============================================================================
// State Types
// ============================================================================

type PanelState = 'collapsed' | 'expanded';

interface SimpleMessage {
  id: string;
  type:
    | 'text'
    | 'error'
    | 'tool_loading'
    | 'tool_result'
    | 'result_contract'
    | 'confirm_card'
    | 'tool_executed'
    | 'tool_cancelled'
    /**
     * Anthropic Extended Thinking trace (P0-2). Carries the chain-of-thought
     * prose returned alongside the assistant turn. Rendered collapsed by
     * default — see {@code ThinkingBlock}.
     */
    | 'thinking';
  sender: 'user' | 'bot' | 'system';
  timestamp: number;
  content: string;
  toolId?: string;
  toolName?: string;
  toolInput?: Record<string, any>;
  toolResult?: Record<string, any>;
  resultContract?: import('../types/ResultContract').ResultContract;
  traceId?: string;
  /**
   * Phase B.6: persisted on confirm_card so confirmTool / cancelTool can echo
   * it back to /execute. Frontend reads this from the SSE confirm_required
   * payload and stores it on the corresponding tool message.
   */
  pendingTurnId?: string;
  /**
   * Anthropic thinking-block fields. Populated only when {@code type === 'thinking'}.
   * {@code thinkingTokens} is the precise count from the SSE event; falls back to a
   * word-count estimate inside the ThinkingBlock component when absent.
   * {@code thinkingSignature} is the opaque resume token Anthropic returns; we
   * persist it for forward compatibility but do not yet round-trip it.
   */
  thinkingTokens?: number;
  thinkingSignature?: string;
}

interface KnowledgeBaseInfo {
  pid: string;
  name: string;
  status: string;
  docCount: number;
}

export interface AuraBotSessionSummary {
  sessionId: string;
  conversationId: number;
  title: string;
  createdAt: number;
  updatedAt: number;
  messageCount: number;
  selectedAgentCode: string;
  lastMessagePreview: string;
}

interface AuraBotState {
  panelState: PanelState;
  sessionId: string;
  currentConversationId: number | null;
  messages: SimpleMessage[];
  isLoading: boolean;
  pageContext: PageContext;
  inputValue: string;
  selectedAgentCode: string;
  selectedKnowledgeBaseIds: string[];
  knowledgeBases: KnowledgeBaseInfo[];
}

// ============================================================================
// Actions
// ============================================================================

type AuraBotAction =
  | { type: 'set_panel_state'; payload: PanelState }
  | { type: 'toggle_panel' }
  | { type: 'add_message'; payload: SimpleMessage }
  | {
      type: 'update_message';
      payload: { id: string; content?: string; type?: SimpleMessage['type']; traceId?: string };
    }
  | { type: 'append_message_content'; payload: { id: string; chunk: string } }
  | { type: 'add_tool_message'; payload: SimpleMessage }
  | { type: 'update_tool_message'; payload: { toolId: string; updates: Partial<SimpleMessage> } }
  | { type: 'clear_messages' }
  | { type: 'set_input_value'; payload: string }
  | { type: 'set_page_context'; payload: PageContext }
  | { type: 'set_loading'; payload: boolean }
  | { type: 'set_selected_agent'; payload: string }
  | { type: 'new_session' }
  | {
      type: 'hydrate_session';
      payload: {
        sessionId: string;
        conversationId: number;
        messages: SimpleMessage[];
        selectedAgentCode: string;
      };
    }
  | { type: 'set_current_conversation'; payload: number | null }
  | { type: 'toggle_knowledge_base'; payload: string }
  | { type: 'set_knowledge_bases'; payload: KnowledgeBaseInfo[] };

// ============================================================================
// Reducer
// ============================================================================

function generateSessionId(): string {
  return `session_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
}

function generateMessageId(): string {
  return `msg_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
}

const LAST_CONVERSATION_KEY = 'aurabot.lastConversationId';

function rememberConversationId(conversationId: number | null) {
  if (typeof window === 'undefined') return;
  if (conversationId == null) {
    window.localStorage.removeItem(LAST_CONVERSATION_KEY);
    return;
  }
  window.localStorage.setItem(LAST_CONVERSATION_KEY, String(conversationId));
}

const defaultPageContext: PageContext = {
  pageType: 'custom',
  pageKey: '/',
  modelCode: '',
  breadcrumb: [],
};

const initialState: AuraBotState = {
  panelState: 'collapsed',
  sessionId: generateSessionId(),
  currentConversationId: null,
  messages: [],
  isLoading: false,
  pageContext: defaultPageContext,
  inputValue: '',
  selectedAgentCode: 'aurabot',
  selectedKnowledgeBaseIds: [],
  knowledgeBases: [],
};

function toSimpleMessage(message: AuraBotConversationMessage): SimpleMessage {
  const sender: SimpleMessage['sender'] =
    message.sender === 'user'
      ? 'user'
      : message.sender === 'system'
        ? 'system'
        : 'bot';

  const normalizedType: SimpleMessage['type'] =
    message.type === 'system'
      ? 'error'
      : message.type === 'ai_response' || message.type === 'text'
        ? 'text'
        : 'text';

  return {
    id: `db-${message.id}`,
    type: normalizedType,
    sender,
    timestamp: new Date(message.createdAt).getTime(),
    content: message.content || '',
    traceId: message.traceId || undefined,
  };
}

function toSessionSummary(item: AuraBotConversationItem): AuraBotSessionSummary {
  const updatedAt = item.updatedAt ? new Date(item.updatedAt).getTime() : Date.now();
  return {
    sessionId: String(item.conversationId),
    conversationId: item.conversationId,
    title: item.title,
    createdAt: updatedAt,
    updatedAt,
    messageCount: item.messageCount,
    selectedAgentCode: item.agentCode,
    lastMessagePreview: item.lastMessagePreview || '',
  };
}

function auraBotReducer(state: AuraBotState, action: AuraBotAction): AuraBotState {
  switch (action.type) {
    case 'set_panel_state':
      return { ...state, panelState: action.payload };

    case 'toggle_panel':
      return {
        ...state,
        panelState: state.panelState === 'collapsed' ? 'expanded' : 'collapsed',
      };

    case 'add_message':
      return { ...state, messages: [...state.messages, action.payload] };

    case 'update_message':
      return {
        ...state,
        messages: state.messages.map((msg) =>
          msg.id === action.payload.id
            ? {
                ...msg,
                ...(action.payload.content !== undefined
                  ? { content: action.payload.content }
                  : {}),
                ...(action.payload.type !== undefined ? { type: action.payload.type } : {}),
                ...(action.payload.traceId !== undefined
                  ? { traceId: action.payload.traceId }
                  : {}),
              }
            : msg,
        ),
      };

    case 'append_message_content':
      return {
        ...state,
        messages: state.messages.map((msg) =>
          msg.id === action.payload.id
            ? { ...msg, content: msg.content + action.payload.chunk }
            : msg,
        ),
      };

    case 'add_tool_message':
      return { ...state, messages: [...state.messages, action.payload] };

    case 'update_tool_message':
      return {
        ...state,
        messages: state.messages.map((msg) =>
          msg.toolId === action.payload.toolId ? { ...msg, ...action.payload.updates } : msg,
        ),
      };

    case 'clear_messages':
      return { ...state, messages: [] };

    case 'set_input_value':
      return { ...state, inputValue: action.payload };

    case 'set_page_context':
      return { ...state, pageContext: action.payload };

    case 'set_loading':
      return { ...state, isLoading: action.payload };

    case 'set_selected_agent':
      return {
        ...state,
        selectedAgentCode: action.payload,
        // Start a new session when switching agents
        sessionId: generateSessionId(),
        currentConversationId: null,
        messages: [],
        inputValue: '',
      };

    case 'new_session':
      return {
        ...state,
        sessionId: generateSessionId(),
        currentConversationId: null,
        messages: [],
        inputValue: '',
      };

    case 'hydrate_session':
      return {
        ...state,
        sessionId: action.payload.sessionId,
        currentConversationId: action.payload.conversationId,
        messages: action.payload.messages,
        selectedAgentCode: action.payload.selectedAgentCode,
        inputValue: '',
      };

    case 'set_current_conversation':
      return { ...state, currentConversationId: action.payload };

    case 'toggle_knowledge_base': {
      const kbId = action.payload;
      const current = state.selectedKnowledgeBaseIds;
      const next = current.includes(kbId)
        ? current.filter((id) => id !== kbId)
        : [...current, kbId];
      return { ...state, selectedKnowledgeBaseIds: next };
    }

    case 'set_knowledge_bases':
      return { ...state, knowledgeBases: action.payload };

    default:
      return state;
  }
}

// ============================================================================
// Context
// ============================================================================

export type FormFillHandler = (fields: Record<string, any>) => void;

/**
 * P1: image attachment carried alongside a user message. Each entry is the
 * raw base64-encoded bytes (no data: URI prefix) plus the original mediaType
 * — the backend rebuilds the Anthropic Messages API content blocks from these
 * fields. {@code name} is purely cosmetic for the bubble preview.
 */
export interface ChatImageAttachment {
  /** MIME type — image/jpeg, image/png, image/gif, image/webp. */
  mediaType: string;
  /** Raw base64 string, no {@code data:image/...;base64,} prefix. */
  data: string;
  /** Original file name for the chip preview (optional). */
  name?: string;
}

interface AuraBotContextValue {
  state: AuraBotState;
  sessions: AuraBotSessionSummary[];
  openPanel: () => void;
  closePanel: () => void;
  togglePanel: () => void;
  sendMessage: (content: string, attachments?: ChatImageAttachment[]) => void;
  confirmTool: (toolId: string) => void;
  cancelTool: (toolId: string) => void;
  clearMessages: () => void;
  newSession: () => void;
  selectSession: (sessionId: string) => void | Promise<void>;
  deleteSession: (sessionId: string) => void | Promise<void>;
  setInputValue: (value: string) => void;
  setPageContext: (ctx: Partial<PageContext>) => void;
  setSelectedAgent: (agentCode: string) => void;
  toggleKnowledgeBase: (kbPid: string) => void;
  registerFormFillHandler: (handler: FormFillHandler) => void;
  unregisterFormFillHandler: () => void;
}

export const AuraBotCtx = createContext<AuraBotContextValue | null>(null);

// ============================================================================
// Route-based PageContext derivation
// ============================================================================

function derivePageContextFromRoute(
  pathname: string,
  params: Record<string, string | undefined>,
): PageContext {
  // /p/:pageKey/view/:recordId → detail
  if (pathname.match(/^\/p\/[^/]+\/view\/[^/]+/)) {
    const pageKey = params.pageKey || '';
    return {
      pageType: 'detail',
      pageKey,
      modelCode: pageKey,
      recordPid: params.recordId,
      breadcrumb: [pageKey],
    };
  }

  // /p/:pageKey/edit/:recordId → form (edit)
  if (pathname.match(/^\/p\/[^/]+\/edit\/[^/]+/)) {
    const pageKey = params.pageKey || '';
    return {
      pageType: 'form',
      pageKey,
      modelCode: pageKey,
      recordPid: params.recordId,
      breadcrumb: [pageKey],
    };
  }

  // /p/:pageKey/new → form (create)
  if (pathname.match(/^\/p\/[^/]+\/new/)) {
    const pageKey = params.pageKey || '';
    return {
      pageType: 'form',
      pageKey,
      modelCode: pageKey,
      breadcrumb: [pageKey],
    };
  }

  // /p/:pageKey → list
  if (pathname.match(/^\/p\/[^/]+$/)) {
    const pageKey = params.pageKey || '';
    return {
      pageType: 'list',
      pageKey,
      modelCode: pageKey,
      breadcrumb: [pageKey],
    };
  }

  // Dashboard pages
  if (pathname.includes('dashboard') || pathname.startsWith('/reports')) {
    return {
      pageType: 'dashboard',
      pageKey: pathname.split('/').pop() || 'dashboard',
      modelCode: '',
      breadcrumb: ['Dashboard'],
    };
  }

  // Default: custom page
  return {
    pageType: 'custom',
    pageKey: pathname,
    modelCode: '',
    breadcrumb: pathname.split('/').filter(Boolean),
  };
}

// ============================================================================
// Provider Component
// ============================================================================

export interface AuraBotProviderProps {
  children: React.ReactNode;
}

export function AuraBotProvider({ children }: AuraBotProviderProps) {
  const [state, dispatch] = useReducer(auraBotReducer, initialState);
  const [sessions, setSessions] = React.useState<AuraBotSessionSummary[]>([]);
  const location = useLocation();
  const params = useParams();
  const formFillHandlerRef = useRef<FormFillHandler | null>(null);

  const refreshConversations = useCallback(async () => {
    try {
      const items = await auraBotApi.listConversations();
      const summaries = items.map(toSessionSummary);
      setSessions(summaries);
      if (!state.currentConversationId && summaries.length > 0 && state.messages.length === 0) {
        // Only auto-restore when we have an explicit remembered conversation id
        // that still exists on the server. Avoid arbitrarily resuming a random
        // historical conversation (breaks welcome-state UX and E2E tests that
        // expect a fresh panel after each reset).
        const preferredConversationId =
          typeof window !== 'undefined'
            ? Number(window.localStorage.getItem(LAST_CONVERSATION_KEY) || '')
            : NaN;
        const target = Number.isFinite(preferredConversationId)
          ? summaries.find((item) => item.conversationId === preferredConversationId)
          : undefined;
        if (target) {
          const messages = await auraBotApi.getConversationMessages(target.conversationId);
          dispatch({
            type: 'hydrate_session',
            payload: {
              sessionId: generateSessionId(),
              conversationId: target.conversationId,
              messages: messages.map(toSimpleMessage),
              selectedAgentCode: target.selectedAgentCode,
            },
          });
        }
      }
    } catch {
      // Conversation history unavailable — keep in-memory state only
    }
  }, [state.currentConversationId, state.messages.length]);

  useEffect(() => {
    if (state.panelState !== 'expanded') return;
    refreshConversations();
  }, [state.panelState, refreshConversations]);

  useEffect(() => {
    rememberConversationId(state.currentConversationId);
  }, [state.currentConversationId]);

  // Sync pageContext from route changes
  useEffect(() => {
    const ctx = derivePageContextFromRoute(location.pathname, params);
    dispatch({ type: 'set_page_context', payload: ctx });
  }, [location.pathname, params]);

  // Panel actions
  const openPanel = useCallback(() => {
    dispatch({ type: 'set_panel_state', payload: 'expanded' });
  }, []);

  const closePanel = useCallback(() => {
    dispatch({ type: 'set_panel_state', payload: 'collapsed' });
  }, []);

  const togglePanel = useCallback(() => {
    dispatch({ type: 'toggle_panel' });
  }, []);

  // Message actions
  const clearMessages = useCallback(() => {
    dispatch({ type: 'clear_messages' });
  }, []);

  // Input
  const setInputValue = useCallback((value: string) => {
    dispatch({ type: 'set_input_value', payload: value });
  }, []);

  // Session
  const newSession = useCallback(() => {
    rememberConversationId(null);
    dispatch({ type: 'new_session' });
  }, []);

  const selectSession = useCallback(
    async (sessionId: string) => {
      const session = sessions.find((item) => item.sessionId === sessionId);
      if (!session) return;
      const messages = await auraBotApi.getConversationMessages(session.conversationId);
      dispatch({
        type: 'hydrate_session',
        payload: {
          sessionId: generateSessionId(),
          conversationId: session.conversationId,
          messages: messages.map(toSimpleMessage),
          selectedAgentCode: session.selectedAgentCode,
        },
      });
      rememberConversationId(session.conversationId);
    },
    [sessions],
  );

  const deleteSession = useCallback(
    async (sessionId: string) => {
      setSessions((prev) => prev.filter((item) => item.sessionId !== sessionId));
      if (String(state.currentConversationId ?? '') === sessionId) {
        rememberConversationId(null);
        dispatch({ type: 'new_session' });
      }
    },
    [state.currentConversationId],
  );

  // Agent selector
  const setSelectedAgent = useCallback((agentCode: string) => {
    dispatch({ type: 'set_selected_agent', payload: agentCode });
  }, []);

  // Knowledge base selector
  const toggleKnowledgeBase = useCallback((kbPid: string) => {
    dispatch({ type: 'toggle_knowledge_base', payload: kbPid });
  }, []);

  // Load knowledge bases when panel expands
  useEffect(() => {
    if (state.panelState !== 'expanded') return;
    const loadKbs = async () => {
      try {
        const res = await fetch('/api/ai/knowledge', { credentials: 'include' });
        if (!res.ok) return;
        const json = await res.json();
        const kbs: KnowledgeBaseInfo[] = (json?.data || [])
          .filter((kb: any) => kb.status === 'active')
          .map((kb: any) => ({
            pid: kb.pid,
            name: kb.name,
            status: kb.status,
            docCount: kb.docCount || 0,
          }));
        dispatch({ type: 'set_knowledge_bases', payload: kbs });
      } catch {
        // KB list unavailable — no-op
      }
    };
    loadKbs();
  }, [state.panelState]);

  // PageContext (manual override)
  const setPageContext = useCallback(
    (ctx: Partial<PageContext>) => {
      dispatch({
        type: 'set_page_context',
        payload: { ...state.pageContext, ...ctx } as PageContext,
      });
    },
    [state.pageContext],
  );

  const ensureConversation = useCallback(async () => {
    if (state.currentConversationId != null) {
      return {
        conversationId: state.currentConversationId,
        agentCode: state.selectedAgentCode,
      };
    }

    const conversation = await auraBotApi.ensureConversation(state.selectedAgentCode);
    const summary = toSessionSummary(conversation);
    setSessions((prev) => {
      const filtered = prev.filter((item) => item.conversationId !== summary.conversationId);
      return [summary, ...filtered];
    });
    rememberConversationId(summary.conversationId);
    dispatch({ type: 'set_current_conversation', payload: summary.conversationId });
    return {
      conversationId: summary.conversationId,
      agentCode: summary.selectedAgentCode,
    };
  }, [state.currentConversationId, state.selectedAgentCode]);

  // Phase B.1: persistAssistantMessage and the appendUserMessage path were
  // deleted. The server now writes both inbound (sender_type=human) and
  // outbound (sender_type=agent) rows from /chat/stream itself via
  // AuraBotTurnPersistence. We just refresh the conversation list after the
  // stream so the new rows render.

  // Send message — wired to SSE streaming
  const sendMessage = useCallback(
    async (content: string, attachments?: ChatImageAttachment[]) => {
      const hasAttachments = !!attachments && attachments.length > 0;
      // Allow empty text when image attachments are present — the model can
      // still answer the implicit "what is this?". Without attachments, we
      // keep the original gating so blank Enter presses are no-ops.
      if (!hasAttachments && !content.trim()) return;
      if (state.isLoading) return;

      const { conversationId } = await ensureConversation();

      // userMsgId doubles as the server-side dedup key (clientMsgId on
      // ChatRequest) so retrying a failed POST does not insert duplicate rows.
      const userMsgId = generateMessageId();
      dispatch({
        type: 'add_message',
        payload: { id: userMsgId, type: 'text', sender: 'user', timestamp: Date.now(), content },
      });
      dispatch({ type: 'set_input_value', payload: '' });
      dispatch({ type: 'set_loading', payload: true });

      // Add empty bot message (will be filled by SSE chunks)
      const botMsgId = generateMessageId();
      dispatch({
        type: 'add_message',
        payload: { id: botMsgId, type: 'text', sender: 'bot', timestamp: Date.now(), content: '' },
      });

      try {
        await auraBotApi.chatStream(
          {
            sessionId: state.sessionId,
            message: content,
            agentCode: state.selectedAgentCode,
            pageContext: state.pageContext,
            // Phase B.1: server-side persistence wiring.
            conversationId,
            clientMsgId: userMsgId,
            knowledgeBaseIds:
              state.selectedKnowledgeBaseIds.length > 0
                ? state.selectedKnowledgeBaseIds
                : undefined,
            history: state.messages
              .filter((m) => m.type === 'text' && m.content)
              .slice(-10)
              .map((m) => ({
                role: m.sender === 'user' ? 'user' : 'assistant',
                content: m.content,
              })),
            // P1 — Vision: image attachments from the AuraBotChat paperclip.
            // Forwarded as-is; the backend translates these into Anthropic
            // Messages API image content blocks. Omitted when empty so the
            // wire shape stays byte-identical with the pre-P1 baseline.
            attachments: hasAttachments ? attachments : undefined,
          },
          {
            onChunk: (chunk: string) => {
              dispatch({ type: 'append_message_content', payload: { id: botMsgId, chunk } });
            },
            onDone: (fullContent: string, traceId?: string) => {
              if (fullContent) {
                dispatch({
                  type: 'update_message',
                  payload: { id: botMsgId, content: fullContent, traceId },
                });
              } else if (traceId) {
                dispatch({ type: 'update_message', payload: { id: botMsgId, traceId } });
              }
              dispatch({ type: 'set_loading', payload: false });
              // Phase B.1: server already persisted the outbound row; just
              // refresh the conversation list so the UI sees the new rows.
              refreshConversations().catch(() => {});
            },
            onError: (error: string, traceId?: string) => {
              dispatch({
                type: 'update_message',
                payload: { id: botMsgId, type: 'error', content: error, traceId },
              });
              dispatch({ type: 'set_loading', payload: false });
              refreshConversations().catch(() => {});
            },
            onToolStart: (toolId: string, toolName: string, input: Record<string, any>) => {
              dispatch({
                type: 'add_tool_message',
                payload: {
                  id: `tool-${toolId}`,
                  type: 'tool_loading',
                  sender: 'system',
                  timestamp: Date.now(),
                  content: `正在查询 ${toolName.replace(/^(nq__|cmd__|builtin__)/, '')}...`,
                  toolId,
                  toolName,
                  toolInput: input,
                },
              });
            },
            onToolResult: (toolId: string, result: Record<string, any>, _success: boolean) => {
              dispatch({
                type: 'update_tool_message',
                payload: {
                  toolId,
                  updates: { type: 'tool_result', content: '', toolResult: result },
                },
              });
              // Handle form_fill action — populate the current page's form
              const data = result?.data || result;
              if (data?.action === 'form_fill' && data?.fields && formFillHandlerRef.current) {
                formFillHandlerRef.current(data.fields);
              }
            },
            onResultContract: (contract) => {
              dispatch({
                type: 'add_tool_message',
                payload: {
                  id: `rc-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                  type: 'result_contract',
                  sender: 'bot',
                  timestamp: Date.now(),
                  content: contract.textSummary ?? '',
                  resultContract: contract,
                },
              });
            },
            onThinking: (content: string, tokens: number, signature?: string) => {
              // P0-2: Anthropic Extended Thinking. Each thinking content block
              // becomes its own collapsible card in the chat surface (rendered
              // by ThinkingBlock). The bot text bubble that follows still
              // receives the streamed text via onChunk, so the user sees
              // "[reasoning toggle] [final answer]" as separate UI entries.
              dispatch({
                type: 'add_message',
                payload: {
                  id: `thinking-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                  type: 'thinking',
                  sender: 'bot',
                  timestamp: Date.now(),
                  content,
                  thinkingTokens: tokens,
                  thinkingSignature: signature,
                },
              });
            },
            onConfirmRequired: (
              toolId: string,
              toolName: string,
              description: string,
              input: Record<string, any>,
              pendingTurnId: string,
            ) => {
              // Phase B.6: capture pendingTurnId on the confirm card so
              // confirmTool / cancelTool can echo it back to /execute.
              dispatch({
                type: 'add_tool_message',
                payload: {
                  id: `confirm-${toolId}`,
                  type: 'confirm_card',
                  sender: 'system',
                  timestamp: Date.now(),
                  content: description,
                  toolId,
                  toolName,
                  toolInput: input,
                  pendingTurnId,
                },
              });
              dispatch({ type: 'set_loading', payload: false });
            },
          },
        );
      } catch (e: any) {
        dispatch({
          type: 'update_message',
          payload: { id: botMsgId, type: 'error', content: e.message || 'Chat failed' },
        });
        dispatch({ type: 'set_loading', payload: false });
        // Phase B.1: server already attempted to persist; on transport failure
        // the row may not have been written, but we don't double-write from
        // the frontend. Refresh so any partial state shows up.
        refreshConversations().catch(() => {});
      }
    },
    [
      ensureConversation,
      refreshConversations,
      state.isLoading,
      state.sessionId,
      state.selectedAgentCode,
      state.pageContext,
      state.messages,
      state.selectedKnowledgeBaseIds,
    ],
  );

  // Confirm a tool execution (user approved)
  const confirmTool = useCallback(
    async (toolId: string) => {
      // Update confirm card to show "executing"
      dispatch({
        type: 'update_tool_message',
        payload: { toolId, updates: { type: 'tool_executed', content: '正在执行...' } },
      });
      dispatch({ type: 'set_loading', payload: true });

      // Add empty bot message for the streaming response
      const botMsgId = 'bot-' + Date.now();
      dispatch({
        type: 'add_message',
        payload: { id: botMsgId, type: 'text', sender: 'bot', timestamp: Date.now(), content: '' },
      });

      // Phase B.6: pendingTurnId was stored on the confirm_card message when
      // the SSE confirm_required event arrived. Look it up by toolId so the
      // /execute call can target the correct suspended turn.
      const confirmCard = state.messages.find(
        (m) => m.type === 'confirm_card' && m.toolId === toolId,
      );
      const pendingTurnId = confirmCard?.pendingTurnId ?? '';
      if (!pendingTurnId) {
        // Defensive: should never happen because the card was just rendered
        // by the SSE handler that set pendingTurnId.
        dispatch({
          type: 'update_message',
          payload: { id: botMsgId, type: 'error', content: 'Missing pendingTurnId for confirmTool' },
        });
        dispatch({ type: 'set_loading', payload: false });
        return;
      }

      try {
        await auraBotApi.executeStream(
          { pendingTurnId, toolId, confirmed: true },
          {
            onChunk: (chunk: string) => {
              dispatch({ type: 'append_message_content', payload: { id: botMsgId, chunk } });
            },
            onToolStart: (tid: string, tname: string, input: Record<string, any>) => {
              dispatch({
                type: 'add_tool_message',
                payload: {
                  id: `tool-${tid}`,
                  type: 'tool_loading',
                  sender: 'system',
                  timestamp: Date.now(),
                  content: `正在查询 ${tname.replace(/^(nq__|cmd__|builtin__)/, '')}...`,
                  toolId: tid,
                  toolName: tname,
                  toolInput: input,
                },
              });
            },
            onToolResult: (tid: string, result: Record<string, any>, _success: boolean) => {
              dispatch({
                type: 'update_tool_message',
                payload: {
                  toolId: tid,
                  updates: { type: 'tool_result', content: '', toolResult: result },
                },
              });
              const data = result?.data || result;
              if (data?.action === 'form_fill' && data?.fields && formFillHandlerRef.current) {
                formFillHandlerRef.current(data.fields);
              }
            },
            onConfirmRequired: (
              tid: string,
              tname: string,
              desc: string,
              input: Record<string, any>,
              pendingTurnId: string,
            ) => {
              dispatch({
                type: 'add_tool_message',
                payload: {
                  id: `confirm-${tid}`,
                  type: 'confirm_card',
                  sender: 'system',
                  timestamp: Date.now(),
                  content: desc,
                  toolId: tid,
                  toolName: tname,
                  toolInput: input,
                  pendingTurnId,
                },
              });
              dispatch({ type: 'set_loading', payload: false });
            },
            onDone: (full: string, traceId?: string) => {
              // Phase B.1 + Q-B1.3=β: /execute (resume) outbound persistence is
              // deferred to B.6, where /execute will go through
              // turnService.resumeTurn and AuraBotTurnPersistence will handle
              // the outbound row. Until then the resume path matches the Phase A
              // invariant of zero server-side persistence — frontend just
              // updates UI state.
              if (full) {
                dispatch({
                  type: 'update_message',
                  payload: { id: botMsgId, content: full, traceId },
                });
              } else if (traceId) {
                dispatch({ type: 'update_message', payload: { id: botMsgId, traceId } });
              }
              dispatch({ type: 'set_loading', payload: false });
            },
            onError: (err: string, traceId?: string) => {
              dispatch({
                type: 'update_message',
                payload: { id: botMsgId, content: err, type: 'error', traceId },
              });
              dispatch({ type: 'set_loading', payload: false });
            },
          },
        );
      } catch (e: any) {
        dispatch({
          type: 'update_message',
          payload: { id: botMsgId, type: 'error', content: e.message || 'Execute failed' },
        });
        dispatch({ type: 'set_loading', payload: false });
      }
    },
    [state.currentConversationId, state.sessionId, state.messages],
  );

  // Cancel a tool execution (user rejected)
  const cancelTool = useCallback(
    async (toolId: string) => {
      dispatch({
        type: 'update_tool_message',
        payload: { toolId, updates: { type: 'tool_cancelled', content: '操作已取消' } },
      });
      // Phase B.6: same pendingTurnId lookup pattern as confirmTool.
      const confirmCard = state.messages.find(
        (m) => m.type === 'confirm_card' && m.toolId === toolId,
      );
      const pendingTurnId = confirmCard?.pendingTurnId ?? '';
      if (!pendingTurnId) {
        return; // Already-handled or missing card; nothing to cancel server-side.
      }
      // Fire and forget — tell backend user cancelled (DENIED).
      auraBotApi.executeStream(
        { pendingTurnId, toolId, confirmed: false },
        { onChunk: () => {}, onDone: () => {}, onError: () => {} },
      );
    },
    [state.messages],
  );

  // Keyboard shortcuts
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Cmd/Ctrl + J: Toggle panel
      if ((e.metaKey || e.ctrlKey) && (e.key.toLowerCase() === 'j' || e.code === 'KeyJ')) {
        e.preventDefault();
        togglePanel();
      }

      // Escape: Close panel
      if (e.key === 'Escape' && state.panelState === 'expanded') {
        e.preventDefault();
        closePanel();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [togglePanel, closePanel, state.panelState]);

  const registerFormFillHandler = useCallback((handler: FormFillHandler) => {
    formFillHandlerRef.current = handler;
  }, []);

  const unregisterFormFillHandler = useCallback(() => {
    formFillHandlerRef.current = null;
  }, []);

  const value: AuraBotContextValue = {
    state,
    sessions,
    openPanel,
    closePanel,
    togglePanel,
    sendMessage,
    confirmTool,
    cancelTool,
    clearMessages,
    newSession,
    selectSession,
    deleteSession,
    setInputValue,
    setPageContext,
    setSelectedAgent,
    toggleKnowledgeBase,
    registerFormFillHandler,
    unregisterFormFillHandler,
  };

  return <AuraBotCtx.Provider value={value}>{children}</AuraBotCtx.Provider>;
}

// ============================================================================
// Hook
// ============================================================================

export function useAuraBot(): AuraBotContextValue {
  const context = useContext(AuraBotCtx);
  if (!context) {
    throw new Error('useAuraBot must be used within AuraBotProvider');
  }
  return context;
}

export type { PanelState, SimpleMessage, AuraBotState, AuraBotContextValue, KnowledgeBaseInfo };
export default AuraBotProvider;
