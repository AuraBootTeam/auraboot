/**
 * G-U1 — useNodeValidationStatus + resolveNodeStateClasses unit tests.
 *
 * Verifies the canvas validation-highlight logic: after a Validate run the
 * store's validationResult.errors[] carry a nodeId; each node resolves its own
 * 'error'/'warning'/null status, mutually exclusive with monitor mode, and the
 * node-state-class precedence is monitor > error > warning > selected.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import {
  useNodeValidationStatus,
  resolveNodeValidationStatus,
  resolveNodeStateClasses,
  getValidationStatusClasses,
} from '../useNodeValidationStatus';
import { useBpmFlowStore } from '~/plugins/core-designer/components/bpm-designer-sdk/store/useBpmFlowStore';
import type { ValidationResult } from '~/plugins/core-designer/components/bpmn-designer/types';

const errorResult: ValidationResult = {
  valid: false,
  errors: [
    { nodeId: 'gw1', message: 'bpmn.validate.exclusive_gateway_min_outgoing', type: 'error' },
    { nodeId: 'task1', message: 'bpmn.validate.task_no_outgoing', type: 'warning' },
  ],
};

describe('resolveNodeValidationStatus (pure)', () => {
  it('returns null when no validation result', () => {
    expect(resolveNodeValidationStatus(null, 'gw1')).toBeNull();
  });

  it('returns null when validation result has no errors', () => {
    expect(resolveNodeValidationStatus({ valid: true, errors: [] }, 'gw1')).toBeNull();
  });

  it('returns "error" for a node with an error', () => {
    expect(resolveNodeValidationStatus(errorResult, 'gw1')).toBe('error');
  });

  it('returns "warning" for a node with only a warning', () => {
    expect(resolveNodeValidationStatus(errorResult, 'task1')).toBe('warning');
  });

  it('returns null for a node with no validation entry', () => {
    expect(resolveNodeValidationStatus(errorResult, 'other')).toBeNull();
  });

  it('prefers error over warning when a node has both', () => {
    const mixed: ValidationResult = {
      valid: false,
      errors: [
        { nodeId: 'n', message: 'w', type: 'warning' },
        { nodeId: 'n', message: 'e', type: 'error' },
      ],
    };
    expect(resolveNodeValidationStatus(mixed, 'n')).toBe('error');
  });
});

describe('getValidationStatusClasses', () => {
  it('maps error -> red ring', () => {
    expect(getValidationStatusClasses('error')).toContain('ring-red-500');
  });
  it('maps warning -> amber ring', () => {
    expect(getValidationStatusClasses('warning')).toContain('ring-amber-500');
  });
  it('maps null -> empty', () => {
    expect(getValidationStatusClasses(null)).toBe('');
  });
});

describe('resolveNodeStateClasses (precedence)', () => {
  it('monitor status wins over everything', () => {
    expect(
      resolveNodeStateClasses({
        monitorStatus: 'active',
        monitorClasses: 'MONITOR',
        validationStatus: 'error',
        selected: true,
      }),
    ).toBe('MONITOR');
  });

  it('error ring when not in monitor mode', () => {
    expect(
      resolveNodeStateClasses({
        monitorStatus: null,
        monitorClasses: '',
        validationStatus: 'error',
        selected: true,
      }),
    ).toContain('ring-red-500');
  });

  it('warning ring when only warning', () => {
    expect(
      resolveNodeStateClasses({
        monitorStatus: null,
        monitorClasses: '',
        validationStatus: 'warning',
        selected: false,
      }),
    ).toContain('ring-amber-500');
  });

  it('selected ring when no monitor/validation', () => {
    expect(
      resolveNodeStateClasses({
        monitorStatus: null,
        monitorClasses: '',
        validationStatus: null,
        selected: true,
      }),
    ).toContain('ring-blue-500');
  });

  it('empty when nothing applies', () => {
    expect(
      resolveNodeStateClasses({
        monitorStatus: null,
        monitorClasses: '',
        validationStatus: null,
        selected: false,
      }),
    ).toBe('');
  });
});

describe('useNodeValidationStatus (via store)', () => {
  beforeEach(() => {
    useBpmFlowStore.setState({ viewMode: 'design', validationResult: null });
  });

  it('returns null when no validation has run', () => {
    const { result } = renderHook(() => useNodeValidationStatus('gw1'));
    expect(result.current).toBeNull();
  });

  it('returns "error" after a failing validation', () => {
    act(() => {
      useBpmFlowStore.setState({ viewMode: 'design', validationResult: errorResult });
    });
    const { result } = renderHook(() => useNodeValidationStatus('gw1'));
    expect(result.current).toBe('error');
  });

  it('returns "warning" for a warning node', () => {
    act(() => {
      useBpmFlowStore.setState({ viewMode: 'design', validationResult: errorResult });
    });
    const { result } = renderHook(() => useNodeValidationStatus('task1'));
    expect(result.current).toBe('warning');
  });

  it('suppresses validation highlight in monitor mode', () => {
    act(() => {
      useBpmFlowStore.setState({ viewMode: 'monitor', validationResult: errorResult });
    });
    const { result } = renderHook(() => useNodeValidationStatus('gw1'));
    expect(result.current).toBeNull();
  });
});
