/**
 * Debug Logger Service
 *
 * Service for logging debug events and action executions.
 *
 * @since 3.2.0
 */

import {
  DEFAULT_DEBUGGER_STATE,
  type LogLevel,
  type DebugLogEntry,
  type ActionExecution,
  type ExecutionStatus,
  type ActionTrigger,
  type DebuggerState,
  type DebugSession,
} from './types';

/**
 * Generate unique ID
 */
function generateId(): string {
  return `${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Debug Logger Class
 */
export class DebugLogger {
  private static instance: DebugLogger;

  private session: DebugSession | null = null;
  private state: DebuggerState = { ...DEFAULT_DEBUGGER_STATE };
  private listeners: Set<(entry: DebugLogEntry | ActionExecution) => void> = new Set();
  private executionStack: ActionExecution[] = [];

  private constructor() {}

  /**
   * Get singleton instance
   */
  public static getInstance(): DebugLogger {
    if (!DebugLogger.instance) {
      DebugLogger.instance = new DebugLogger();
    }
    return DebugLogger.instance;
  }

  /**
   * Start debug session
   */
  public startSession(): DebugSession {
    this.session = {
      id: generateId(),
      startTime: Date.now(),
      executions: [],
      logs: [],
      state: { ...this.state },
    };
    return this.session;
  }

  /**
   * End debug session
   */
  public endSession(): DebugSession | null {
    if (!this.session) return null;

    this.session.endTime = Date.now();
    const session = { ...this.session };
    return session;
  }

  /**
   * Get current session
   */
  public getSession(): DebugSession | null {
    return this.session;
  }

  /**
   * Get debugger state
   */
  public getState(): DebuggerState {
    return { ...this.state };
  }

  /**
   * Update debugger state
   */
  public setState(updates: Partial<DebuggerState>): void {
    this.state = { ...this.state, ...updates };
    if (this.session) {
      this.session.state = { ...this.state };
    }
  }

  /**
   * Enable/disable debugger
   */
  public setEnabled(enabled: boolean): void {
    this.setState({ enabled });
    if (enabled && !this.session) {
      this.startSession();
    }
  }

  /**
   * Log a message
   */
  public log(
    level: LogLevel,
    message: string,
    data?: Record<string, unknown>,
    options: { actionId?: string; componentId?: string } = {},
  ): DebugLogEntry | null {
    if (!this.state.enabled) return null;
    if (!this.state.logLevelFilter.includes(level)) return null;

    const entry: DebugLogEntry = {
      id: generateId(),
      timestamp: Date.now(),
      level,
      message,
      data,
      actionId: options.actionId,
      componentId: options.componentId,
    };

    this.addLog(entry);
    return entry;
  }

  /**
   * Log info
   */
  public info(message: string, data?: Record<string, unknown>): DebugLogEntry | null {
    return this.log('info', message, data);
  }

  /**
   * Log debug
   */
  public debug(message: string, data?: Record<string, unknown>): DebugLogEntry | null {
    return this.log('debug', message, data);
  }

  /**
   * Log warning
   */
  public warn(message: string, data?: Record<string, unknown>): DebugLogEntry | null {
    return this.log('warn', message, data);
  }

  /**
   * Log error
   */
  public error(message: string, error?: Error | unknown): DebugLogEntry | null {
    const entry = this.log('error', message, {
      error: error instanceof Error ? error.message : String(error),
    });

    if (entry && error instanceof Error) {
      entry.stack = error.stack;
    }

    if (this.state.pauseOnError) {
      console.warn('[DebugLogger] Pausing on error:', message);
      // debugger; // Uncomment to enable breakpoint
    }

    return entry;
  }

  /**
   * Start action execution
   */
  public startExecution(
    actionId: string,
    actionType: string,
    trigger: ActionTrigger,
    options: { label?: string; input?: Record<string, unknown> } = {},
  ): ActionExecution | null {
    if (!this.state.enabled) return null;

    const execution: ActionExecution = {
      id: generateId(),
      actionId,
      actionType,
      actionLabel: options.label,
      trigger,
      status: 'running',
      startTime: Date.now(),
      input: options.input,
      logs: [],
      children: [],
    };

    // Add to current parent if exists
    const parent = this.executionStack[this.executionStack.length - 1];
    if (parent) {
      parent.children = parent.children || [];
      parent.children.push(execution);
    } else if (this.session) {
      this.session.executions.push(execution);
    }

    this.executionStack.push(execution);
    this.emit(execution);

    this.log('debug', `Action started: ${actionType}`, { actionId, trigger }, { actionId });

    return execution;
  }

  /**
   * End action execution
   */
  public endExecution(
    executionId: string,
    status: ExecutionStatus,
    result?: { output?: unknown; error?: string },
  ): ActionExecution | null {
    const index = this.executionStack.findIndex((e) => e.id === executionId);
    if (index === -1) return null;

    const execution = this.executionStack[index];
    execution.status = status;
    execution.endTime = Date.now();
    execution.duration = execution.endTime - execution.startTime;

    if (result?.output !== undefined) {
      execution.output = result.output;
    }
    if (result?.error) {
      execution.error = result.error;
    }

    // Remove from stack
    this.executionStack.splice(index, 1);

    const logLevel = status === 'failed' ? 'error' : 'debug';
    this.log(
      logLevel,
      `Action ${status}: ${execution.actionType} (${execution.duration}ms)`,
      { executionId, status, duration: execution.duration },
      { actionId: execution.actionId },
    );

    this.emit(execution);
    return execution;
  }

  /**
   * Add log to current context
   */
  private addLog(entry: DebugLogEntry): void {
    if (!this.session) return;

    // Add to session logs
    this.session.logs.push(entry);

    // Add to current execution if exists
    const currentExecution = this.executionStack[this.executionStack.length - 1];
    if (currentExecution && entry.actionId === currentExecution.actionId) {
      currentExecution.logs.push(entry);
    }

    // Trim logs if exceeds max
    if (this.session.logs.length > this.state.maxLogEntries) {
      this.session.logs = this.session.logs.slice(-this.state.maxLogEntries);
    }

    this.emit(entry);
  }

  /**
   * Get all logs
   */
  public getLogs(): DebugLogEntry[] {
    return this.session?.logs || [];
  }

  /**
   * Get all executions
   */
  public getExecutions(): ActionExecution[] {
    return this.session?.executions || [];
  }

  /**
   * Clear logs
   */
  public clearLogs(): void {
    if (this.session) {
      this.session.logs = [];
    }
  }

  /**
   * Clear executions
   */
  public clearExecutions(): void {
    if (this.session) {
      this.session.executions = [];
    }
  }

  /**
   * Clear all
   */
  public clear(): void {
    this.clearLogs();
    this.clearExecutions();
    this.executionStack = [];
  }

  /**
   * Subscribe to events
   */
  public subscribe(listener: (entry: DebugLogEntry | ActionExecution) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * Emit event
   */
  private emit(entry: DebugLogEntry | ActionExecution): void {
    this.listeners.forEach((listener) => {
      try {
        listener(entry);
      } catch (e) {
        console.error('[DebugLogger] Listener error:', e);
      }
    });
  }

  /**
   * Export session data
   */
  public exportSession(): string {
    if (!this.session) return '{}';
    return JSON.stringify(this.session, null, 2);
  }
}

// Export singleton instance
export const debugLogger = DebugLogger.getInstance();

export default debugLogger;
