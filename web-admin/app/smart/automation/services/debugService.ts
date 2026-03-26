/**
 * Debug Service
 *
 * API calls for automation debug sessions.
 */

import { get, post, put } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { DebugSession, DebugSessionCreateRequest } from '../debug/types';

const BASE_URL = '/api/automation';

function handleResponse<T>(
  result: { code: string; data: T | null; desc?: string },
  errorMsg: string,
): T {
  if (ResultHelper.isSuccess(result) && result.data !== null) {
    return result.data;
  }
  throw new Error(result.desc || errorMsg);
}

export class DebugService {
  /** Create a debug session */
  async createSession(
    automationId: string,
    request: DebugSessionCreateRequest,
  ): Promise<DebugSession> {
    const result = await post<DebugSession>(`${BASE_URL}/${automationId}/debug/sessions`, request);
    return handleResponse(result, 'Failed to create debug session');
  }

  /** Get session state */
  async getSession(sessionId: string): Promise<DebugSession> {
    const result = await get<DebugSession>(`${BASE_URL}/debug/sessions/${sessionId}`);
    return handleResponse(result, 'Failed to get debug session');
  }

  /** Step: execute next action */
  async step(sessionId: string): Promise<DebugSession> {
    const result = await post<DebugSession>(`${BASE_URL}/debug/sessions/${sessionId}/step`);
    return handleResponse(result, 'Failed to step');
  }

  /** Continue until breakpoint or completion */
  async continue(sessionId: string): Promise<DebugSession> {
    const result = await post<DebugSession>(`${BASE_URL}/debug/sessions/${sessionId}/continue`);
    return handleResponse(result, 'Failed to continue');
  }

  /** Stop the debug session */
  async stop(sessionId: string): Promise<DebugSession> {
    const result = await post<DebugSession>(`${BASE_URL}/debug/sessions/${sessionId}/stop`);
    return handleResponse(result, 'Failed to stop');
  }

  /** Restart the session */
  async restart(sessionId: string): Promise<DebugSession> {
    const result = await post<DebugSession>(`${BASE_URL}/debug/sessions/${sessionId}/restart`);
    return handleResponse(result, 'Failed to restart');
  }

  /** Get execution context */
  async getContext(sessionId: string): Promise<Record<string, unknown>> {
    const result = await get<Record<string, unknown>>(
      `${BASE_URL}/debug/sessions/${sessionId}/context`,
    );
    return handleResponse(result, 'Failed to get context');
  }

  /** Update breakpoints */
  async updateBreakpoints(sessionId: string, breakpoints: number[]): Promise<DebugSession> {
    const result = await put<DebugSession>(
      `${BASE_URL}/debug/sessions/${sessionId}/breakpoints`,
      breakpoints,
    );
    return handleResponse(result, 'Failed to update breakpoints');
  }

  /** Get SSE events URL */
  getEventsUrl(sessionId: string): string {
    return `${BASE_URL}/debug/sessions/${sessionId}/events`;
  }
}

export const debugService = new DebugService();
