/**
 * AuraBot API Service
 *
 * Handles communication with the backend AuraBot API.
 * Supports SSE streaming for real-time responses.
 *
 * @since 1.0.0
 */

import type {
  ChatRequest,
  ChatOptions,
  ExecuteRequest,
  ExecuteResponse,
  UndoRequest,
  UndoResponse,
  AuraBotContext,
  IntentResult,
  OperationStep,
} from '../types';

// ============================================================================
// Configuration
// ============================================================================

const API_BASE_URL = '/api/ai/aurabot';

// ============================================================================
// Types
// ============================================================================

/**
 * SSE event types from the server
 */
export type SSEEventType =
  | 'thinking'
  | 'intent'
  | 'preview'
  | 'result'
  | 'confirm_required'
  | 'error'
  | 'done';

/**
 * SSE event data
 */
export interface SSEEvent {
  type: SSEEventType;
  data: unknown;
}

/**
 * Chat stream callbacks
 */
export interface ChatStreamCallbacks {
  onThinking?: (message: string) => void;
  onIntent?: (intent: IntentResult) => void;
  onPreview?: (steps: OperationStep[]) => void;
  onResult?: (result: unknown) => void;
  onConfirmRequired?: (data: { message: string; actions: string[] }) => void;
  onError?: (error: { code?: string; message: string }) => void;
  onDone?: () => void;
}

/**
 * Simple chat stream callbacks for the copilot panel.
 * Supports tool-calling SSE events (tool_start, tool_result, confirm_required).
 */
export interface ChatStreamOptions {
  onChunk: (chunk: string) => void;
  onDone: (fullContent: string, traceId?: string) => void;
  onError: (error: string, traceId?: string) => void;
  onToolStart?: (toolId: string, toolName: string, input: Record<string, any>) => void;
  onToolResult?: (toolId: string, result: Record<string, any>, success: boolean) => void;
  onConfirmRequired?: (
    toolId: string,
    toolName: string,
    description: string,
    input: Record<string, any>,
  ) => void;
}

// ============================================================================
// API Client
// ============================================================================

/**
 * AuraBot API client
 */
export const auraBotApi = {
  /**
   * Send a chat message and receive streaming response
   */
  async chat(
    request: ChatRequest,
    callbacks: ChatStreamCallbacks,
    signal?: AbortSignal,
  ): Promise<void> {
    const response = await fetch(`${API_BASE_URL}/chat`, {
      method: 'post',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      credentials: 'include',
      body: JSON.stringify(request),
      signal,
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed' }));
      callbacks.onError?.({ message: error.message || `HTTP ${response.status}` });
      return;
    }

    // Handle SSE stream
    const reader = response.body?.getReader();
    if (!reader) {
      callbacks.onError?.({ message: 'No response body' });
      return;
    }

    const decoder = new TextDecoder();
    let buffer = '';

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('event:')) {
            // Event type line, will be followed by data
            continue;
          }
          if (line.startsWith('data:')) {
            const dataStr = line.slice(5).trim();
            if (!dataStr) continue;

            try {
              const event = JSON.parse(dataStr) as SSEEvent;
              handleSSEEvent(event, callbacks);
            } catch (parseError) {
              console.error('Failed to parse SSE data:', parseError);
            }
          }
        }
      }
    } finally {
      reader.releaseLock();
    }

    callbacks.onDone?.();
  },

  /**
   * Send a chat message (non-streaming)
   */
  async chatSync(
    sessionId: string,
    message: string,
    context: Partial<AuraBotContext>,
    options?: ChatOptions,
  ): Promise<IntentResult> {
    const response = await fetch(`${API_BASE_URL}/chat/sync`, {
      method: 'post',
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include',
      body: JSON.stringify({
        sessionId,
        message,
        context,
        options,
      } as ChatRequest),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed' }));
      throw new Error(error.message || `HTTP ${response.status}`);
    }

    const result = await response.json();
    return result.data as IntentResult;
  },

  /**
   * Execute planned operations
   */
  async execute(request: ExecuteRequest): Promise<ExecuteResponse> {
    const response = await fetch(`${API_BASE_URL}/execute`, {
      method: 'post',
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include',
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed' }));
      throw new Error(error.message || `HTTP ${response.status}`);
    }

    const result = await response.json();
    return result.data as ExecuteResponse;
  },

  /**
   * Undo previous operations
   */
  async undo(request: UndoRequest): Promise<UndoResponse> {
    const response = await fetch(`${API_BASE_URL}/undo`, {
      method: 'post',
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include',
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed' }));
      throw new Error(error.message || `HTTP ${response.status}`);
    }

    const result = await response.json();
    return result.data as UndoResponse;
  },

  /**
   * Start a wizard flow
   */
  async startWizard(
    sessionId: string,
    wizard: string,
    params: Record<string, unknown>,
    callbacks: {
      onStep?: (step: { step: number; total: number; description: string; status: string }) => void;
      onComplete?: (result: unknown) => void;
      onError?: (error: { message: string }) => void;
    },
    signal?: AbortSignal,
  ): Promise<void> {
    const response = await fetch(`${API_BASE_URL}/wizard`, {
      method: 'post',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      credentials: 'include',
      body: JSON.stringify({ sessionId, wizard, params }),
      signal,
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed' }));
      callbacks.onError?.({ message: error.message || `HTTP ${response.status}` });
      return;
    }

    const reader = response.body?.getReader();
    if (!reader) {
      callbacks.onError?.({ message: 'No response body' });
      return;
    }

    const decoder = new TextDecoder();
    let buffer = '';

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const dataStr = line.slice(5).trim();
            if (!dataStr) continue;

            try {
              const data = JSON.parse(dataStr);
              if (data.type === 'step') {
                callbacks.onStep?.(data);
              } else if (data.type === 'completed') {
                callbacks.onComplete?.(data);
              } else if (data.type === 'error') {
                callbacks.onError?.({ message: data.message });
              }
            } catch (parseError) {
              console.error('Failed to parse wizard event:', parseError);
            }
          }
        }
      }
    } finally {
      reader.releaseLock();
    }
  },

  /**
   * Get available skills
   */
  async getSkills(): Promise<unknown[]> {
    const response = await fetch(`${API_BASE_URL}/skills`, {
      method: 'get',
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include',
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    const result = await response.json();
    return result.data || [];
  },

  /**
   * Search resources for mentions
   */
  async searchResources(
    type: 'model' | 'field' | 'page' | 'command' | 'dict' | 'role',
    query: string,
  ): Promise<Array<{ code: string; name: string; description?: string }>> {
    const response = await fetch(
      `${API_BASE_URL}/resources/search?type=${encodeURIComponent(type)}&query=${encodeURIComponent(query)}`,
      {
        method: 'get',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include' as RequestCredentials,
      },
    );

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    const result = await response.json();
    return result.data || [];
  },

  /**
   * Get session history
   */
  async getHistory(
    sessionId: string,
  ): Promise<
    Array<{ id: string; skill: string; description: string; timestamp: number; undoToken?: string }>
  > {
    const response = await fetch(`${API_BASE_URL}/sessions/${sessionId}/history`, {
      method: 'get',
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include',
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    const result = await response.json();
    return result.data || [];
  },

  /**
   * Send a chat message and receive SSE streaming response (simple callbacks).
   * Used by AuraBotProvider.sendMessage for the copilot panel.
   */
  async chatStream(
    request: {
      sessionId: string;
      message: string;
      agentCode?: string;
      pageContext?: any;
      knowledgeBaseIds?: string[];
      history?: Array<{ role: string; content: string }>;
    },
    callbacks: ChatStreamOptions,
  ): Promise<void> {
    const response = await fetch(`${API_BASE_URL}/chat/stream`, {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      credentials: 'include',
    });

    if (!response.ok) {
      callbacks.onError(`HTTP ${response.status}: ${response.statusText}`);
      return;
    }

    const reader = response.body?.getReader();
    if (!reader) {
      callbacks.onError('No response body');
      return;
    }

    const decoder = new TextDecoder();
    let fullContent = '';
    let buffer = '';

    await processSSEStream(reader, decoder, buffer, fullContent, callbacks);
  },

  /**
   * Execute a tool call (confirm or cancel) and receive SSE streaming response.
   * Used after a confirm_required event to tell the backend whether the user approved.
   */
  async executeStream(
    request: { sessionId: string; toolId: string; confirmed: boolean },
    callbacks: ChatStreamOptions,
  ): Promise<void> {
    const response = await fetch(`${API_BASE_URL}/execute`, {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      credentials: 'include',
    });

    if (!response.ok) {
      callbacks.onError(`HTTP ${response.status}: ${response.statusText}`);
      return;
    }

    const reader = response.body?.getReader();
    if (!reader) {
      callbacks.onError('No response body');
      return;
    }

    const decoder = new TextDecoder();
    let fullContent = '';
    let buffer = '';

    await processSSEStream(reader, decoder, buffer, fullContent, callbacks);
  },
};

// ============================================================================
// Helpers
// ============================================================================

/**
 * Shared SSE stream processor for chatStream and executeStream.
 * Handles event-typed SSE lines (event: xxx / data: {...}) and plain data-only lines.
 */
async function processSSEStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  decoder: TextDecoder,
  buffer: string,
  fullContent: string,
  callbacks: ChatStreamOptions,
): Promise<void> {
  let currentEvent = '';
  let traceId: string | undefined;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.substring(6).trim();
          continue;
        }
        if (line.startsWith('data:')) {
          const dataStr = line.substring(5).trim();
          if (!dataStr) continue;

          try {
            const data = JSON.parse(dataStr);

            // Capture traceId from any event that carries it
            if (data.traceId) traceId = data.traceId;

            // Route based on event name (if present) or fall back to field-based detection
            switch (currentEvent) {
              case 'tool_start':
                callbacks.onToolStart?.(data.toolId, data.toolName, data.input || {});
                break;
              case 'tool_result':
                callbacks.onToolResult?.(data.toolId, data.result || {}, data.success !== false);
                break;
              case 'confirm_required':
                callbacks.onConfirmRequired?.(
                  data.toolId,
                  data.toolName,
                  data.description || '',
                  data.input || {},
                );
                break;
              case 'done':
                // done event — fullContent already accumulated, traceId captured above
                break;
              case 'error':
                callbacks.onError(data.error || data.message || 'Unknown error', traceId);
                return;
              case 'chunk':
              default:
                // Default: treat as content chunk (backward compatible)
                if (data.content) {
                  fullContent += data.content;
                  callbacks.onChunk(data.content);
                }
                if (data.error) {
                  callbacks.onError(data.error, traceId);
                  return;
                }
                break;
            }
          } catch {
            // Non-JSON data line, ignore
          }

          // Reset event after processing its data line
          currentEvent = '';
        }
      }
    }
  } finally {
    reader.releaseLock();
  }

  callbacks.onDone(fullContent, traceId);
}

function handleSSEEvent(event: SSEEvent, callbacks: ChatStreamCallbacks): void {
  switch (event.type) {
    case 'thinking':
      callbacks.onThinking?.(event.data as string);
      break;
    case 'intent':
      callbacks.onIntent?.(event.data as IntentResult);
      break;
    case 'preview':
      callbacks.onPreview?.(event.data as OperationStep[]);
      break;
    case 'result':
      callbacks.onResult?.(event.data);
      break;
    case 'confirm_required':
      callbacks.onConfirmRequired?.(event.data as { message: string; actions: string[] });
      break;
    case 'error':
      callbacks.onError?.(event.data as { code?: string; message: string });
      break;
    case 'done':
      callbacks.onDone?.();
      break;
  }
}

export default auraBotApi;
