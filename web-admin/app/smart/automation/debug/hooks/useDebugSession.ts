/**
 * useDebugSession - Zustand store for debug session state management.
 */

import { create } from 'zustand';
import type { DebugSession, DebugEvent, DebugSessionCreateRequest } from '../types';
import { debugService } from '../../services/debugService';

interface DebugSessionState {
  /** Current debug session */
  session: DebugSession | null;
  /** Whether debug mode is active */
  isDebugMode: boolean;
  /** Loading state for async operations */
  loading: boolean;
  /** Error message */
  error: string | null;
  /** Event log */
  events: DebugEvent[];

  // Actions
  startDebug: (automationId: string, request?: DebugSessionCreateRequest) => Promise<void>;
  step: () => Promise<void>;
  continueExecution: () => Promise<void>;
  stop: () => Promise<void>;
  restart: () => Promise<void>;
  updateBreakpoints: (breakpoints: number[]) => Promise<void>;
  refreshSession: () => Promise<void>;
  exitDebugMode: () => void;
  addEvent: (event: DebugEvent) => void;
  updateSessionFromEvent: (event: DebugEvent) => void;
}

export const useDebugSession = create<DebugSessionState>((set, getState) => ({
  session: null,
  isDebugMode: false,
  loading: false,
  error: null,
  events: [],

  startDebug: async (automationId: string, request?: DebugSessionCreateRequest) => {
    set({ loading: true, error: null, events: [] });
    try {
      const session = await debugService.createSession(automationId, request || {});
      set({ session, isDebugMode: true, loading: false });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : 'Failed to start debug', loading: false });
    }
  },

  step: async () => {
    const { session } = getState();
    if (!session) return;
    set({ loading: true, error: null });
    try {
      const updated = await debugService.step(session.pid);
      set({ session: updated, loading: false });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : 'Step failed', loading: false });
    }
  },

  continueExecution: async () => {
    const { session } = getState();
    if (!session) return;
    set({ loading: true, error: null });
    try {
      const updated = await debugService.continue(session.pid);
      set({ session: updated, loading: false });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : 'Continue failed', loading: false });
    }
  },

  stop: async () => {
    const { session } = getState();
    if (!session) return;
    set({ loading: true, error: null });
    try {
      const updated = await debugService.stop(session.pid);
      set({ session: updated, loading: false, isDebugMode: false });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : 'Stop failed', loading: false });
    }
  },

  restart: async () => {
    const { session } = getState();
    if (!session) return;
    set({ loading: true, error: null, events: [] });
    try {
      const updated = await debugService.restart(session.pid);
      set({ session: updated, loading: false });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : 'Restart failed', loading: false });
    }
  },

  updateBreakpoints: async (breakpoints: number[]) => {
    const { session } = getState();
    if (!session) return;
    try {
      const updated = await debugService.updateBreakpoints(session.pid, breakpoints);
      set({ session: updated });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : 'Failed to update breakpoints' });
    }
  },

  refreshSession: async () => {
    const { session } = getState();
    if (!session) return;
    try {
      const updated = await debugService.getSession(session.pid);
      set({ session: updated });
    } catch (e) {
      // Silently ignore refresh errors
    }
  },

  exitDebugMode: () => {
    set({ session: null, isDebugMode: false, loading: false, error: null, events: [] });
  },

  addEvent: (event: DebugEvent) => {
    set((state) => ({ events: [...state.events, event] }));
  },

  updateSessionFromEvent: (event: DebugEvent) => {
    const { session } = getState();
    if (!session || event.sessionId !== session.pid) return;

    const updates: Partial<DebugSession> = {};

    if (event.context) {
      updates.executionContext = event.context;
    }

    if (event.eventType === 'session_paused') {
      updates.status = 'paused';
    } else if (event.eventType === 'session_completed') {
      updates.status = 'completed';
    } else if (event.eventType === 'session_stopped') {
      updates.status = 'stopped';
    } else if (event.eventType === 'action_failed' && event.errorMessage) {
      updates.errorMessage = event.errorMessage;
    }

    if (event.actionIndex !== undefined) {
      updates.currentActionIndex = event.actionIndex;
    }

    set({ session: { ...session, ...updates } });
  },
}));
