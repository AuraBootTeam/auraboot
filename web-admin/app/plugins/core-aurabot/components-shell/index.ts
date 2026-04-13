/**
 * AuraBot - AI Agent Assistant
 *
 * Global AI assistant for AuraBoot low-code platform.
 * Provides natural language interaction for configuration tasks.
 *
 * @since 1.0.0
 */

// Provider
export { AuraBotProvider, useAuraBot } from './AuraBotProvider';
export type { AuraBotProviderProps } from './AuraBotProvider';

// Components
export { AuraBotPanel } from './AuraBotPanel';

export { AuraBotChat } from '../components-internal/AuraBotChat';

// Services
export { auraBotApi } from '../services/auraBotApi';
export type { SSEEvent, SSEEventType, ChatStreamCallbacks } from '../services/auraBotApi';

// Types
export type {
  // Panel
  PanelState,
  PanelPosition,

  // Messages
  MessageSender,
  MessageType,
  Message,
  MessageInput,
  TextMessage,
  ThinkingMessage,
  PreviewMessage,
  ResultMessage,
  ConfirmMessage,
  SuggestionMessage,
  WizardProgressMessage,
  ErrorMessage,
  CodeMessage,
  DiffMessage,

  // Operations
  OperationStep,
  OperationChange,
  OperationResult,
  ConfirmAction,
  QuickAction,
  WizardStep,

  // Context
  PageContext,
  PageType,
  ResourceRef,
  AuraBotContext,
  OperationLog,

  // Mentions
  MentionType,
  MentionItem,

  // Session
  ChatSession,

  // API
  ChatRequest,
  ChatOptions,
  ExecuteRequest,
  ExecuteResponse,
  UndoRequest,
  UndoResponse,

  // Skills
  SkillCategory,
  SkillMeta,
  SkillParam,
  IntentResult,
} from '../types';
