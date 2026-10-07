import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cmp, evaluatePreview, lit, path } from '../../ast/conditionAst';
import type { EventPolicySummary } from '../../api/decisionApi';
import type { TestSample } from '../ConditionTestRunPanel';
import { defaultSamplesForPolicy, useDefaultTestSamples } from '../defaultTestSamples';

const get = vi.fn();

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => ({
    get,
    post: vi.fn(),
    delete: vi.fn(),
  }),
}));

function recordData(context: TestSample['context'] | undefined): Record<string, unknown> {
  return ((context?.record as Record<string, unknown> | undefined)?.data ??
    {}) as Record<string, unknown>;
}

function policy(overrides: Partial<EventPolicySummary> = {}): EventPolicySummary {
  return {
    policyCode: 'complaint_policy',
    policyName: 'Complaint Policy',
    eventType: 'FORM_SUBMITTED',
    targetType: 'FORM',
    targetKey: 'complaint',
    status: 'PUBLISHED',
    ...overrides,
  } as EventPolicySummary;
}

describe('defaultSamplesForPolicy', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('returns no samples when no policy is selected (host contract)', () => {
    expect(defaultSamplesForPolicy(null)).toEqual([]);
  });

  it('provides a generic record sample with amount=9000 matching the baseline designer', () => {
    const [sample] = defaultSamplesForPolicy(policy());
    expect(sample.label).toBe('默认样例');
    expect(recordData(sample.context)).toMatchObject({
      priority: 'HIGH',
      amount: 9000,
      status: 'OPEN',
    });
    expect(sample.context.record && (sample.context.record as Record<string, unknown>).entityCode).toBe('complaint');
  });

  it('generic sample is constructible into a preview hit and a miss', () => {
    const [sample] = defaultSamplesForPolicy(policy());
    const hit = cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(100, 'decimal'));
    const miss = cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(99999, 'decimal'));
    expect(evaluatePreview(hit, sample.context)).toBe('TRUE');
    expect(evaluatePreview(miss, sample.context)).toBe('FALSE');
  });

  it('provides the leave request sample with resolved applicant and run context', () => {
    const [sample] = defaultSamplesForPolicy(
      policy({ policyCode: 'leave_request_event_policy', targetKey: 'wd_leave_request' }),
      '01APPLICANT',
    );
    expect(sample.label).toBe('5天长假申请');
    expect(recordData(sample.context)).toMatchObject({
      wd_req_days: 5,
      wd_req_applicant: '01APPLICANT',
    });
    const runContext = sample.executionContext?.();
    expect(String(recordData(runContext).recordPid)).toMatch(/^REQ-LONG-LEAVE-SAMPLE-RUN-/);
  });
});

describe('useDefaultTestSamples', () => {
  beforeEach(() => {
    get.mockReset();
    get.mockResolvedValue({ data: {} });
  });

  it('falls back to a generic sample before a policy is selected so the preview stays constructible', () => {
    const { result } = renderHook(() => useDefaultTestSamples(null));
    expect(result.current).toHaveLength(1);
    expect(recordData(result.current[0].context)).toMatchObject({ amount: 9000 });
    expect(get).not.toHaveBeenCalled();
  });

  it('uses policy defaults without caller samples for non-leave policies', () => {
    const { result } = renderHook(() => useDefaultTestSamples(policy()));
    expect(result.current).toHaveLength(1);
    expect(result.current[0].label).toBe('默认样例');
    expect(get).not.toHaveBeenCalled();
  });

  it('resolves the leave applicant so the leave sample can hit applicant conditions', async () => {
    get.mockResolvedValue({
      data: {
        records: [
          { pid: '01AGENT', displayName: 'Agent: helper' },
          { pid: '01ADMINUSERPID', email: 'admin@auraboot.com', nickName: 'Admin' },
        ],
      },
    });
    const { result } = renderHook(() =>
      useDefaultTestSamples(policy({ policyCode: 'leave_request_event_policy', targetKey: 'wd_leave_request' })),
    );

    await waitFor(() => expect(get).toHaveBeenCalledWith('/admin/users/search', {
      keyword: '',
      page: 1,
      size: 20,
    }));
    await waitFor(() =>
      expect(recordData(result.current[0].context)).toMatchObject({
        wd_req_applicant: '01ADMINUSERPID',
      }),
    );
  });
});
