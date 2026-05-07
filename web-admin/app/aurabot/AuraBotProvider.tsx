import type { ReactElement } from 'react';
/**
 * AuraBot V3 Shell Provider.
 *
 * Owns the panel state machine, the message scroll buffer, and the
 * traceId → message-id index used to replace envelopes in place during
 * SSE streaming.
 *
 * SSR-safe: initial state is `hidden`; localStorage-backed persistence
 * runs in a `useEffect` only after mount. No top-level `window` access.
 *
 * Named `AuraBotShellProvider` to avoid clashing with the legacy V2
 * `AuraBotProvider` exported from `~/plugins/core-aurabot/components-shell`.
 * Both providers can co-exist while V3 ramps up; root.tsx wires V3 alongside
 * (not replacing) the V2 conversation surface.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  type ReactNode,
} from 'react';
import type { Envelope, Message } from './types/envelope';
import { isPanelState, PANEL_STATE_STORAGE_KEY, type PanelState } from './types/panel';

interface AuraBotShellState {
  panelState: PanelState;
  messages: Message[];
  /** Maps wire-level traceId to the message id carrying that turn. */
  traceIdMap: Record<string, string>;
}

type Action =
  | { type: 'set_panel_state'; payload: PanelState }
  | { type: 'append_message'; payload: Message }
  | {
      type: 'replace_envelope_by_traceid';
      payload: { traceId: string; envelope: Envelope; matchKind?: Envelope['kind'] };
    }
  | {
      type: 'append_envelope_by_traceid';
      payload: { traceId: string; envelope: Envelope };
    }
  | { type: 'clear_messages' };

const initialState: AuraBotShellState = {
  panelState: 'hidden',
  messages: [],
  traceIdMap: {},
};

function reducer(state: AuraBotShellState, action: Action): AuraBotShellState {
  switch (action.type) {
    case 'set_panel_state':
      return { ...state, panelState: action.payload };

    case 'append_message': {
      const traceIdMap =
        action.payload.traceId != null
          ? { ...state.traceIdMap, [action.payload.traceId]: action.payload.id }
          : state.traceIdMap;
      return {
        ...state,
        messages: [...state.messages, action.payload],
        traceIdMap,
      };
    }

    case 'replace_envelope_by_traceid': {
      const { traceId, envelope, matchKind } = action.payload;
      const targetId = state.traceIdMap[traceId];
      if (!targetId) return state;
      return {
        ...state,
        messages: state.messages.map((msg) => {
          if (msg.id !== targetId) return msg;
          // Replace first envelope matching kind, else append.
          const idx = matchKind
            ? msg.envelopes.findIndex((env) => env.kind === matchKind)
            : -1;
          if (idx >= 0) {
            const next = [...msg.envelopes];
            next[idx] = envelope;
            return { ...msg, envelopes: next };
          }
          return { ...msg, envelopes: [...msg.envelopes, envelope] };
        }),
      };
    }

    case 'append_envelope_by_traceid': {
      const { traceId, envelope } = action.payload;
      const targetId = state.traceIdMap[traceId];
      if (!targetId) return state;
      return {
        ...state,
        messages: state.messages.map((msg) =>
          msg.id === targetId ? { ...msg, envelopes: [...msg.envelopes, envelope] } : msg,
        ),
      };
    }

    case 'clear_messages':
      return { ...state, messages: [], traceIdMap: {} };

    default:
      return state;
  }
}

export interface AuraBotShellContextValue {
  panelState: PanelState;
  messages: Message[];
  traceIdMap: Record<string, string>;
  setPanelState: (next: PanelState) => void;
  appendMessage: (msg: Message) => void;
  replaceEnvelopeByTraceId: (
    traceId: string,
    envelope: Envelope,
    matchKind?: Envelope['kind'],
  ) => void;
  appendEnvelopeByTraceId: (traceId: string, envelope: Envelope) => void;
  clearMessages: () => void;
}

const AuraBotShellContext = createContext<AuraBotShellContextValue | null>(null);

interface AuraBotShellProviderProps {
  children: ReactNode;
}

export function AuraBotShellProvider({ children }: AuraBotShellProviderProps): ReactElement {
  const [state, dispatch] = useReducer(reducer, initialState);
  const restoredRef = useRef(false);

  // Hydrate panel state from localStorage on mount only — keep SSR clean.
  useEffect(() => {
    if (restoredRef.current) return;
    restoredRef.current = true;
    try {
      const raw = window.localStorage.getItem(PANEL_STATE_STORAGE_KEY);
      if (raw && isPanelState(raw)) {
        dispatch({ type: 'set_panel_state', payload: raw });
      }
    } catch {
      // Storage unavailable — keep default.
    }
  }, []);

  // Persist state changes after the initial hydration tick.
  useEffect(() => {
    if (!restoredRef.current) return;
    try {
      window.localStorage.setItem(PANEL_STATE_STORAGE_KEY, state.panelState);
    } catch {
      // Storage full / blocked — no-op; state still works in memory.
    }
  }, [state.panelState]);

  const setPanelState = useCallback((next: PanelState) => {
    dispatch({ type: 'set_panel_state', payload: next });
  }, []);

  const appendMessage = useCallback((msg: Message) => {
    dispatch({ type: 'append_message', payload: msg });
  }, []);

  const replaceEnvelopeByTraceId = useCallback(
    (traceId: string, envelope: Envelope, matchKind?: Envelope['kind']) => {
      dispatch({
        type: 'replace_envelope_by_traceid',
        payload: { traceId, envelope, matchKind },
      });
    },
    [],
  );

  const appendEnvelopeByTraceId = useCallback((traceId: string, envelope: Envelope) => {
    dispatch({ type: 'append_envelope_by_traceid', payload: { traceId, envelope } });
  }, []);

  const clearMessages = useCallback(() => {
    dispatch({ type: 'clear_messages' });
  }, []);

  const value = useMemo<AuraBotShellContextValue>(
    () => ({
      panelState: state.panelState,
      messages: state.messages,
      traceIdMap: state.traceIdMap,
      setPanelState,
      appendMessage,
      replaceEnvelopeByTraceId,
      appendEnvelopeByTraceId,
      clearMessages,
    }),
    [
      state.panelState,
      state.messages,
      state.traceIdMap,
      setPanelState,
      appendMessage,
      replaceEnvelopeByTraceId,
      appendEnvelopeByTraceId,
      clearMessages,
    ],
  );

  return (
    <AuraBotShellContext.Provider value={value}>{children}</AuraBotShellContext.Provider>
  );
}

export function useAuraBotShell(): AuraBotShellContextValue {
  const ctx = useContext(AuraBotShellContext);
  if (!ctx) {
    throw new Error('useAuraBotShell must be used within AuraBotShellProvider');
  }
  return ctx;
}
