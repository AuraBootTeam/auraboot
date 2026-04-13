/**
 * Action Debugger Types
 *
 * Type definitions for action debugging.
 *
 * @since 3.2.0
 */

/**
 * Log level
 */
export type LogLevel = 'info' | 'debug' | 'warn' | 'error';

/**
 * Action execution status
 */
export type ExecutionStatus = 'pending' | 'running' | 'success' | 'failed' | 'skipped';

/**
 * Debug log entry
 */
export interface DebugLogEntry {
  /** Unique ID */
  id: string;
  /** Timestamp */
  timestamp: number;
  /** Log level */
  level: LogLevel;
  /** Log message */
  message: string;
  /** Related action ID */
  actionId?: string;
  /** Related component ID */
  componentId?: string;
  /** Additional data */
  data?: Record<string, unknown>;
  /** Stack trace (for errors) */
  stack?: string;
}

/**
 * Action execution record
 */
export interface ActionExecution {
  /** Execution ID */
  id: string;
  /** Action ID */
  actionId: string;
  /** Action type */
  actionType: string;
  /** Action label */
  actionLabel?: string;
  /** Trigger source */
  trigger: ActionTrigger;
  /** Execution status */
  status: ExecutionStatus;
  /** Start timestamp */
  startTime: number;
  /** End timestamp */
  endTime?: number;
  /** Duration in ms */
  duration?: number;
  /** Input parameters */
  input?: Record<string, unknown>;
  /** Output result */
  output?: unknown;
  /** Error message */
  error?: string;
  /** Child actions */
  children?: ActionExecution[];
  /** Related logs */
  logs: DebugLogEntry[];
}

/**
 * Action trigger source
 */
export interface ActionTrigger {
  /** Trigger type */
  type: 'click' | 'change' | 'load' | 'submit' | 'custom';
  /** Source component ID */
  componentId?: string;
  /** Source component type */
  componentType?: string;
  /** Event name */
  eventName?: string;
}

/**
 * Debugger state
 */
export interface DebuggerState {
  /** Whether debugger is enabled */
  enabled: boolean;
  /** Whether to pause on error */
  pauseOnError: boolean;
  /** Whether to capture all events */
  captureAll: boolean;
  /** Log level filter */
  logLevelFilter: LogLevel[];
  /** Maximum log entries */
  maxLogEntries: number;
}

/**
 * Action breakpoint
 */
export interface ActionBreakpoint {
  /** Breakpoint ID */
  id: string;
  /** Action ID to break on */
  actionId: string;
  /** Condition expression */
  condition?: string;
  /** Whether breakpoint is enabled */
  enabled: boolean;
}

/**
 * Debug session
 */
export interface DebugSession {
  /** Session ID */
  id: string;
  /** Start timestamp */
  startTime: number;
  /** End timestamp */
  endTime?: number;
  /** All executions */
  executions: ActionExecution[];
  /** All logs */
  logs: DebugLogEntry[];
  /** Session state */
  state: DebuggerState;
}

/**
 * Default debugger state
 */
export const DEFAULT_DEBUGGER_STATE: DebuggerState = {
  enabled: false,
  pauseOnError: false,
  captureAll: false,
  logLevelFilter: ['info', 'debug', 'warn', 'error'],
  maxLogEntries: 1000,
};

/**
 * Log level colors
 */
export const LOG_LEVEL_COLORS: Record<LogLevel, { bg: string; text: string; icon: string }> = {
  info: { bg: 'bg-blue-50', text: 'text-blue-600', icon: 'ℹ' },
  debug: { bg: 'bg-gray-50', text: 'text-gray-600', icon: '🔍' },
  warn: { bg: 'bg-yellow-50', text: 'text-yellow-600', icon: '⚠' },
  error: { bg: 'bg-red-50', text: 'text-red-600', icon: '✕' },
};

/**
 * Execution status colors
 */
export const EXECUTION_STATUS_COLORS: Record<
  ExecutionStatus,
  { bg: string; text: string; icon: string }
> = {
  pending: { bg: 'bg-gray-100', text: 'text-gray-500', icon: '○' },
  running: { bg: 'bg-blue-100', text: 'text-blue-600', icon: '⟳' },
  success: { bg: 'bg-green-100', text: 'text-green-600', icon: '✓' },
  failed: { bg: 'bg-red-100', text: 'text-red-600', icon: '✕' },
  skipped: { bg: 'bg-gray-100', text: 'text-gray-400', icon: '⊘' },
};
