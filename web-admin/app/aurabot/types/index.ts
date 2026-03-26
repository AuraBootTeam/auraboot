/**
 * AuraBot Type Definitions
 *
 * Core types for the AuraBot AI assistant.
 *
 * @since 1.0.0
 */

// ============================================================================
// Panel State
// ============================================================================

/**
 * Panel visibility state
 */
export type PanelState = 'hidden' | 'expanded' | 'pinned' | 'fullscreen';

/**
 * Panel position for floating mode
 */
export interface PanelPosition {
  x: number;
  y: number;
}

// ============================================================================
// Messages
// ============================================================================

/**
 * Message sender type
 */
export type MessageSender = 'user' | 'bot' | 'system';

/**
 * Message type
 */
export type MessageType =
  | 'text'
  | 'thinking'
  | 'preview'
  | 'result'
  | 'confirm'
  | 'suggestion'
  | 'wizard-progress'
  | 'error'
  | 'code'
  | 'diff';

/**
 * Base message interface
 */
export interface BaseMessage {
  id: string;
  type: MessageType;
  sender: MessageSender;
  timestamp: number;
}

/**
 * Text message
 */
export interface TextMessage extends BaseMessage {
  type: 'text';
  content: string;
}

/**
 * Thinking message (shows AI reasoning)
 */
export interface ThinkingMessage extends BaseMessage {
  type: 'thinking';
  content: string;
  isComplete: boolean;
}

/**
 * Preview message (shows planned operations)
 */
export interface PreviewMessage extends BaseMessage {
  type: 'preview';
  steps: OperationStep[];
  canExecute: boolean;
}

/**
 * Result message (shows operation results)
 */
export interface ResultMessage extends BaseMessage {
  type: 'result';
  success: boolean;
  results: OperationResult[];
  undoToken?: string;
  suggestions?: string[];
}

/**
 * Confirm message (requires user confirmation)
 */
export interface ConfirmMessage extends BaseMessage {
  type: 'confirm';
  title: string;
  description: string;
  actions: ConfirmAction[];
  riskLevel: 'low' | 'medium' | 'high' | 'critical';
}

/**
 * Suggestion message
 */
export interface SuggestionMessage extends BaseMessage {
  type: 'suggestion';
  suggestions: QuickAction[];
}

/**
 * Wizard progress message
 */
export interface WizardProgressMessage extends BaseMessage {
  type: 'wizard-progress';
  wizardId: string;
  wizardName: string;
  currentStep: number;
  totalSteps: number;
  steps: WizardStep[];
  canPause: boolean;
  canCancel: boolean;
}

/**
 * Error message
 */
export interface ErrorMessage extends BaseMessage {
  type: 'error';
  title: string;
  message: string;
  code?: string;
  retryable: boolean;
}

/**
 * Code message
 */
export interface CodeMessage extends BaseMessage {
  type: 'code';
  language: string;
  code: string;
  title?: string;
}

/**
 * Diff message
 */
export interface DiffMessage extends BaseMessage {
  type: 'diff';
  title: string;
  before: string;
  after: string;
}

/**
 * Union type for all messages
 */
export type Message =
  | TextMessage
  | ThinkingMessage
  | PreviewMessage
  | ResultMessage
  | ConfirmMessage
  | SuggestionMessage
  | WizardProgressMessage
  | ErrorMessage
  | CodeMessage
  | DiffMessage;

/**
 * Message input types (for creating messages without id/timestamp)
 */
export type TextMessageInput = Omit<TextMessage, 'id' | 'timestamp'>;
export type ThinkingMessageInput = Omit<ThinkingMessage, 'id' | 'timestamp'>;
export type PreviewMessageInput = Omit<PreviewMessage, 'id' | 'timestamp'>;
export type ResultMessageInput = Omit<ResultMessage, 'id' | 'timestamp'>;
export type ConfirmMessageInput = Omit<ConfirmMessage, 'id' | 'timestamp'>;
export type SuggestionMessageInput = Omit<SuggestionMessage, 'id' | 'timestamp'>;
export type WizardProgressMessageInput = Omit<WizardProgressMessage, 'id' | 'timestamp'>;
export type ErrorMessageInput = Omit<ErrorMessage, 'id' | 'timestamp'>;
export type CodeMessageInput = Omit<CodeMessage, 'id' | 'timestamp'>;
export type DiffMessageInput = Omit<DiffMessage, 'id' | 'timestamp'>;

/**
 * Union type for message inputs
 */
export type MessageInput =
  | TextMessageInput
  | ThinkingMessageInput
  | PreviewMessageInput
  | ResultMessageInput
  | ConfirmMessageInput
  | SuggestionMessageInput
  | WizardProgressMessageInput
  | ErrorMessageInput
  | CodeMessageInput
  | DiffMessageInput;

// ============================================================================
// Operations & Skills
// ============================================================================

/**
 * Operation step in preview
 */
export interface OperationStep {
  skill: string;
  description: string;
  changes: OperationChange[];
  params?: Record<string, unknown>;
}

/**
 * Operation change detail
 */
export interface OperationChange {
  type: 'model' | 'field' | 'page' | 'command' | 'dict' | 'role' | 'menu' | 'binding';
  action: 'create' | 'update' | 'delete';
  target: string;
  details?: string;
}

/**
 * Operation result
 */
export interface OperationResult {
  skill: string;
  success: boolean;
  description: string;
  data?: Record<string, unknown>;
  error?: string;
  undoToken?: string;
}

/**
 * Confirm action
 */
export interface ConfirmAction {
  id: string;
  label: string;
  type: 'primary' | 'secondary' | 'danger';
}

/**
 * Quick action suggestion
 */
export interface QuickAction {
  id: string;
  label: string;
  icon?: string;
  command?: string;
}

/**
 * Wizard step
 */
export interface WizardStep {
  index: number;
  name: string;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'skipped';
  description?: string;
  result?: Record<string, unknown>;
  error?: string;
}

// ============================================================================
// Context
// ============================================================================

/**
 * Current page context
 */
export interface PageContext {
  route: string;
  pageType: PageType;
  title?: string;
}

/**
 * Page type enum
 */
export type PageType =
  | 'home'
  | 'model-list'
  | 'model-detail'
  | 'page-list'
  | 'page-designer'
  | 'command-list'
  | 'command-detail'
  | 'dict-list'
  | 'dict-detail'
  | 'role-list'
  | 'role-detail'
  | 'menu-list'
  | 'unknown';

/**
 * Resource reference
 */
export interface ResourceRef {
  type: 'model' | 'field' | 'page' | 'command' | 'dict' | 'role' | 'menu';
  code: string;
  name?: string;
  pid?: string;
}

/**
 * AuraBot context
 */
export interface AuraBotContext {
  // Page context
  page: PageContext;

  // Current resources
  currentModel?: ResourceRef;
  currentPage?: ResourceRef;
  currentCommand?: ResourceRef;
  selectedElement?: string;

  // Recent resources
  recentModels: ResourceRef[];
  recentPages: ResourceRef[];
  recentOperations: OperationLog[];

  // Available resources (for suggestions)
  availableModels?: ResourceRef[];
  availableDicts?: ResourceRef[];

  // User context
  userId?: string;
  tenantId?: string;
}

/**
 * Operation log entry
 */
export interface OperationLog {
  id: string;
  skill: string;
  description: string;
  timestamp: number;
  undoToken?: string;
  canUndo: boolean;
}

// ============================================================================
// Mentions
// ============================================================================

/**
 * Mention type
 */
export type MentionType = 'model' | 'field' | 'page' | 'command' | 'dict' | 'role';

/**
 * Mention item
 */
export interface MentionItem {
  type: MentionType;
  code: string;
  name: string;
  description?: string;
}

// ============================================================================
// Session
// ============================================================================

/**
 * Chat session
 */
export interface ChatSession {
  id: string;
  messages: Message[];
  context: AuraBotContext;
  createdAt: number;
  updatedAt: number;
}

// ============================================================================
// API Types
// ============================================================================

/**
 * Chat request
 */
export interface ChatRequest {
  sessionId: string;
  message: string;
  context: Partial<AuraBotContext>;
  options?: ChatOptions;
}

/**
 * Chat options
 */
export interface ChatOptions {
  autoExecute?: boolean;
  showPreview?: boolean;
  language?: string;
}

/**
 * Execute request
 */
export interface ExecuteRequest {
  sessionId: string;
  steps: OperationStep[];
  confirmed: boolean;
}

/**
 * Execute response
 */
export interface ExecuteResponse {
  success: boolean;
  results: OperationResult[];
  batchUndoToken?: string;
}

/**
 * Undo request
 */
export interface UndoRequest {
  sessionId: string;
  undoToken: string;
}

/**
 * Undo response
 */
export interface UndoResponse {
  success: boolean;
  undoneSteps: Array<{
    skill: string;
    description: string;
  }>;
}

// ============================================================================
// Skill Types
// ============================================================================

/**
 * Skill category
 */
export type SkillCategory =
  | 'model'
  | 'field'
  | 'dict'
  | 'page'
  | 'command'
  | 'permission'
  | 'menu'
  | 'query'
  | 'action'
  | 'datasource'
  | 'wizard'
  | 'help';

/**
 * Skill metadata
 */
export interface SkillMeta {
  id: string;
  name: string;
  category: SkillCategory;
  description: string;
  examples: string[];
  params: SkillParam[];
}

/**
 * Skill parameter
 */
export interface SkillParam {
  name: string;
  type: 'string' | 'number' | 'boolean' | 'array' | 'object';
  required: boolean;
  description: string;
  defaultValue?: unknown;
}

/**
 * Intent parse result
 */
export interface IntentResult {
  intent: string;
  skills: Array<{
    skill: string;
    confidence: number;
    params: Record<string, unknown>;
    dependsOn?: string;
  }>;
  clarification?: string;
  suggestions?: string[];
}
