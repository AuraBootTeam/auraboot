import { describe, expect, it } from 'vitest';
import { act } from '@testing-library/react';
import { renderHook } from '@testing-library/react';
import { AllProviders } from './test-utils';
import { useAuraBotShell } from '../AuraBotProvider';
import type { Envelope } from '../types/envelope';

describe('AuraBotShellProvider', () => {
  it('initial state is hidden with empty messages', () => {
    const { result } = renderHook(() => useAuraBotShell(), {
      wrapper: AllProviders,
    });
    expect(result.current.panelState).toBe('hidden');
    expect(result.current.messages).toEqual([]);
  });

  it('appendMessage indexes traceId for later replacement', () => {
    const { result } = renderHook(() => useAuraBotShell(), {
      wrapper: AllProviders,
    });

    const initialEnvelope: Envelope = { kind: 'thinking', text: 'pondering' };
    act(() => {
      result.current.appendMessage({
        id: 'msg_1',
        traceId: 'trace_a',
        role: 'assistant',
        envelopes: [initialEnvelope],
      });
    });
    expect(result.current.messages).toHaveLength(1);
    expect(result.current.traceIdMap).toEqual({ trace_a: 'msg_1' });

    const replaced: Envelope = { kind: 'text', text: 'final' };
    act(() => {
      result.current.replaceEnvelopeByTraceId('trace_a', replaced, 'thinking');
    });
    expect(result.current.messages[0].envelopes[0]).toEqual(replaced);
  });

  it('replace by traceId without matching kind appends instead', () => {
    const { result } = renderHook(() => useAuraBotShell(), {
      wrapper: AllProviders,
    });
    act(() => {
      result.current.appendMessage({
        id: 'msg_2',
        traceId: 'trace_b',
        role: 'assistant',
        envelopes: [{ kind: 'text', text: 'one' }],
      });
      result.current.replaceEnvelopeByTraceId(
        'trace_b',
        { kind: 'suggestion', suggestions: [] },
        'thinking',
      );
    });
    const envs = result.current.messages[0].envelopes;
    expect(envs).toHaveLength(2);
    expect(envs[1].kind).toBe('suggestion');
  });

  it('setPanelState transitions and persists to localStorage', () => {
    const { result } = renderHook(() => useAuraBotShell(), {
      wrapper: AllProviders,
    });
    act(() => result.current.setPanelState('pinned'));
    expect(result.current.panelState).toBe('pinned');
    expect(window.localStorage.getItem('aurabot.shell.panelState')).toBe('pinned');
  });
});
