import { renderHook, act } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('~/shared/services/http-client', () => ({
  get: vi.fn(),
}));

vi.mock('~/utils/type', () => ({
  ResultHelper: {
    isSuccess: vi.fn((r: any) => r?.code === 200 || r?.success === true),
  },
}));

import { useFieldAutoFill } from '../useFieldAutoFill';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { AutoFillFieldConfig } from '../useFieldAutoFill';

const mockGet = vi.mocked(get);
const mockIsSuccess = vi.mocked(ResultHelper.isSuccess);

const accountField: AutoFillFieldConfig = {
  code: 'account_id',
  fieldType: 'reference',
  extension: {
    autoFill: {
      trigger: 'onChange',
      source: { modelCode: 'crm_account', recordPidField: 'account_id' },
      mappings: [
        { sourceField: 'industry', targetField: 'opp_industry' },
        { sourceField: 'city', targetField: 'opp_city' },
      ],
    },
  },
};

describe('useFieldAutoFill', () => {
  beforeEach(() => {
    mockGet.mockReset();
    mockIsSuccess.mockReset();
  });

  it('does not call get when form values are empty', () => {
    const setFormValue = vi.fn();
    renderHook(() =>
      useFieldAutoFill([accountField], {}, setFormValue),
    );
    expect(mockGet).not.toHaveBeenCalled();
  });

  it('does not call get when autoFill trigger field has no value', () => {
    const setFormValue = vi.fn();
    renderHook(() =>
      useFieldAutoFill([accountField], { account_id: '' }, setFormValue),
    );
    expect(mockGet).not.toHaveBeenCalled();
  });

  it('calls GET when trigger field changes to a non-empty value', async () => {
    mockGet.mockResolvedValue({ code: 200, data: { industry: 'Tech', city: 'Beijing' } } as any);
    mockIsSuccess.mockReturnValue(true);

    const setFormValue = vi.fn();
    const { rerender } = renderHook(
      ({ fv }: { fv: Record<string, unknown> }) =>
        useFieldAutoFill([accountField], fv, setFormValue),
      { initialProps: { fv: {} } },
    );

    // Simulate the trigger field being set
    rerender({ fv: { account_id: 'acc-123' } });

    // Wait for the async effect
    await act(async () => {
      await Promise.resolve();
    });

    expect(mockGet).toHaveBeenCalledOnce();
    expect(mockGet.mock.calls[0][0]).toContain('/api/meta/auto-fill');
    expect(mockGet.mock.calls[0][0]).toContain('modelCode=crm_account');
    expect(mockGet.mock.calls[0][0]).toContain('recordPid=acc-123');
  });

  it('fills empty target fields from source record', async () => {
    mockGet.mockResolvedValue({ code: 200, data: { industry: 'Tech', city: 'Shanghai' } } as any);
    mockIsSuccess.mockReturnValue(true);

    const setFormValue = vi.fn();
    const { rerender } = renderHook(
      ({ fv }: { fv: Record<string, unknown> }) =>
        useFieldAutoFill([accountField], fv, setFormValue),
      { initialProps: { fv: {} } },
    );

    rerender({ fv: { account_id: 'acc-456', opp_industry: '', opp_city: '' } });

    await act(async () => {
      await Promise.resolve();
    });

    expect(setFormValue).toHaveBeenCalledWith('opp_industry', 'Tech');
    expect(setFormValue).toHaveBeenCalledWith('opp_city', 'Shanghai');
  });

  it('does not overwrite non-empty target fields', async () => {
    mockGet.mockResolvedValue({ code: 200, data: { industry: 'Finance', city: 'Beijing' } } as any);
    mockIsSuccess.mockReturnValue(true);

    const setFormValue = vi.fn();
    const { rerender } = renderHook(
      ({ fv }: { fv: Record<string, unknown> }) =>
        useFieldAutoFill([accountField], fv, setFormValue),
      { initialProps: { fv: {} } },
    );

    // opp_industry already has a value — should NOT be overwritten
    rerender({ fv: { account_id: 'acc-789', opp_industry: 'Existing', opp_city: '' } });

    await act(async () => {
      await Promise.resolve();
    });

    const industryCall = setFormValue.mock.calls.find((c) => c[0] === 'opp_industry');
    expect(industryCall).toBeUndefined();
    // opp_city is empty — should be filled
    expect(setFormValue).toHaveBeenCalledWith('opp_city', 'Beijing');
  });

  it('does not re-fire when unrelated form fields change', async () => {
    mockGet.mockResolvedValue({ code: 200, data: { industry: 'Tech' } } as any);
    mockIsSuccess.mockReturnValue(true);

    const setFormValue = vi.fn();
    const { rerender } = renderHook(
      ({ fv }: { fv: Record<string, unknown> }) =>
        useFieldAutoFill([accountField], fv, setFormValue),
      { initialProps: { fv: { account_id: 'acc-111' } } },
    );

    await act(async () => await Promise.resolve());
    const firstCallCount = mockGet.mock.calls.length;

    // Change an unrelated field
    rerender({ fv: { account_id: 'acc-111', some_other_field: 'value' } });
    await act(async () => await Promise.resolve());

    // account_id hasn't changed, so no new fetch
    expect(mockGet).toHaveBeenCalledTimes(firstCallCount);
  });

  it('does not call setFormValue when API call fails', async () => {
    mockGet.mockRejectedValue(new Error('Network error'));

    const setFormValue = vi.fn();
    const { rerender } = renderHook(
      ({ fv }: { fv: Record<string, unknown> }) =>
        useFieldAutoFill([accountField], fv, setFormValue),
      { initialProps: { fv: {} } },
    );

    rerender({ fv: { account_id: 'acc-err' } });

    await act(async () => {
      await Promise.resolve();
    });

    expect(setFormValue).not.toHaveBeenCalled();
  });

  it('does not call setFormValue when API returns non-success result', async () => {
    mockGet.mockResolvedValue({ code: 500, data: null } as any);
    mockIsSuccess.mockReturnValue(false);

    const setFormValue = vi.fn();
    const { rerender } = renderHook(
      ({ fv }: { fv: Record<string, unknown> }) =>
        useFieldAutoFill([accountField], fv, setFormValue),
      { initialProps: { fv: {} } },
    );

    rerender({ fv: { account_id: 'acc-bad' } });

    await act(async () => {
      await Promise.resolve();
    });

    expect(setFormValue).not.toHaveBeenCalled();
  });

  it('ignores fields without autoFill config', () => {
    const plainField: AutoFillFieldConfig = { code: 'name', fieldType: 'text' };
    const setFormValue = vi.fn();

    const { rerender } = renderHook(
      ({ fv }: { fv: Record<string, unknown> }) =>
        useFieldAutoFill([plainField], fv, setFormValue),
      { initialProps: { fv: {} } },
    );

    rerender({ fv: { name: 'John' } });
    expect(mockGet).not.toHaveBeenCalled();
  });
});
