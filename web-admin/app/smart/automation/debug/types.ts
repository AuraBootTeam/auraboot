/**
 * Debug session types for automation step-through debugging.
 */

import type { ActionResult } from '../services/automationService';

/** Debug session status */
export type DebugStatus = 'paused' | 'running' | 'completed' | 'failed' | 'stopped';

/** Debug session state */
export interface DebugSession {
  pid: string;
  automationId: string;
  recordId?: string;
  status: DebugStatus;
  currentActionIndex: number;
  totalActions: number;
  breakpoints: number[];
  executionContext: Record<string, unknown>;
  actionResults: ActionResult[];
  triggerPayload: Record<string, unknown>;
  errorMessage?: string;
  createdAt: string;
  updatedAt: string;
}

/** Request to create a debug session */
export interface DebugSessionCreateRequest {
  recordId?: string;
  breakpoints?: number[];
  triggerPayload?: Record<string, unknown>;
}

/** SSE event from debug session */
export interface DebugEvent {
  eventType:
    | 'action_started'
    | 'action_completed'
    | 'action_failed'
    | 'session_paused'
    | 'session_completed'
    | 'session_stopped'
    | 'connected'
    | 'heartbeat';
  sessionId: string;
  actionIndex?: number;
  actionType?: string;
  actionLabel?: string;
  actionResult?: ActionResult;
  context?: Record<string, unknown>;
  errorMessage?: string;
  timestamp?: string;
}

/** Debug status display config */
export const debugStatusConfig: Record<string, { label: string; color: string; bgColor: string }> =
  {
    paused: { label: 'Paused', color: 'text-yellow-700', bgColor: 'bg-yellow-100' },
    running: { label: 'Running', color: 'text-blue-700', bgColor: 'bg-blue-100' },
    completed: { label: 'Completed', color: 'text-green-700', bgColor: 'bg-green-100' },
    failed: { label: 'Failed', color: 'text-red-700', bgColor: 'bg-red-100' },
    stopped: { label: 'Stopped', color: 'text-gray-700', bgColor: 'bg-gray-100' },
  };
