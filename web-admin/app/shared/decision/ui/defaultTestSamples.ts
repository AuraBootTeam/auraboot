import { useEffect, useMemo, useState } from 'react';
import { getApiService } from '~/shared/services/ApiService';
import type { EventPolicySummary } from '../api/decisionApi';
import type { TestSample } from './ConditionTestRunPanel';

/**
 * Default test samples for the event policy designer workflow. Hosts that do not receive
 * caller-provided samples fall back to these so the three-valued condition preview
 * (TRUE/FALSE/UNKNOWN) always has constructible hit/miss evidence, matching the
 * baseline designer behavior (record sample with amount=9000).
 */

interface UserOption {
  pid?: string;
  id?: string | number;
  displayName?: string;
  name?: string;
  realName?: string;
  nickName?: string;
  nickname?: string;
  username?: string;
  userName?: string;
  email?: string;
}

function asUserList(raw: unknown): UserOption[] {
  if (Array.isArray(raw)) return raw as UserOption[];
  if (raw && typeof raw === 'object') {
    const record = raw as Record<string, unknown>;
    if (Array.isArray(record.records)) return record.records as UserOption[];
    if (Array.isArray(record.rows)) return record.rows as UserOption[];
    if (Array.isArray(record.content)) return record.content as UserOption[];
    if (Array.isArray(record.data)) return record.data as UserOption[];
  }
  return [];
}

function userLabel(user: UserOption): string {
  return String(
    user.displayName ??
      user.realName ??
      user.nickName ??
      user.nickname ??
      user.name ??
      user.username ??
      user.userName ??
      user.email ??
      user.pid ??
      user.id ??
      '',
  );
}

function preferredSampleUser(users: UserOption[]): UserOption | undefined {
  return (
    users.find((user) => String(user.email ?? '').toLowerCase() === 'admin@auraboot.com') ??
    users.find((user) => !userLabel(user).startsWith('Agent:') && (user.pid || user.id)) ??
    users.find((user) => user.pid || user.id)
  );
}

function leaveRequestSampleContext(recordPid: string, applicantPid?: string): TestSample['context'] {
  return {
    record: {
      modelCode: 'wd_leave_request',
      entityCode: 'wd_leave_request',
      recordPid,
      data: {
        entityCode: 'wd_leave_request',
        recordPid,
        wd_req_no: recordPid,
        wd_req_days: 5,
        ...(applicantPid ? { wd_req_applicant: applicantPid } : {}),
      },
    },
  };
}

function leaveRequestRunContext(applicantPid?: string): TestSample['context'] {
  return leaveRequestSampleContext(
    `REQ-LONG-LEAVE-SAMPLE-RUN-${Date.now().toString(36)}`,
    applicantPid,
  );
}

function defaultRecordSample(policy: EventPolicySummary | null): TestSample {
  const targetKey = policy?.targetKey || 'record';
  const recordPid = `TEST-${policy?.policyCode || targetKey}`;
  return {
    label: '默认样例',
    context: {
      event: {
        type: policy?.eventType,
      },
      record: {
        entityCode: targetKey,
        recordPid,
        data: {
          entityCode: targetKey,
          recordPid,
          priority: 'HIGH',
          amount: 9000,
          status: 'OPEN',
        },
      },
    },
  };
}

export function defaultSamplesForPolicy(
  policy: EventPolicySummary | null,
  sampleApplicantPid?: string,
): TestSample[] {
  if (!policy) return [];
  if (policy.policyCode === 'leave_request_event_policy' || policy.targetKey === 'wd_leave_request') {
    const recordPid = 'REQ-LONG-LEAVE-SAMPLE';
    return [
      {
        label: '5天长假申请',
        context: leaveRequestSampleContext(recordPid, sampleApplicantPid),
        executionContext: () => leaveRequestRunContext(sampleApplicantPid),
      },
    ];
  }
  return [defaultRecordSample(policy)];
}

/**
 * Fallback used when neither the host nor the selected policy yields a sample (e.g. the
 * console designer tab before a policy is picked): a generic record sample that conditions
 * on amount/priority/status can match.
 */
const GENERIC_TEST_SAMPLES: TestSample[] = [defaultRecordSample(null)];

export function useDefaultTestSamples(policy: EventPolicySummary | null): TestSample[] {
  const [sampleApplicantPid, setSampleApplicantPid] = useState<string | undefined>();
  const policyTargetKey = policy?.targetKey;

  useEffect(() => {
    if (policyTargetKey !== 'wd_leave_request') {
      setSampleApplicantPid(undefined);
      return;
    }
    let cancelled = false;
    getApiService()
      .get<unknown>('/admin/users/search', { keyword: '', page: 1, size: 20 })
      .then((result) => {
        if (cancelled) return;
        const user = preferredSampleUser(asUserList(result.data));
        const pid = user?.pid ?? user?.id;
        setSampleApplicantPid(pid == null ? undefined : String(pid));
      })
      .catch(() => {
        if (!cancelled) setSampleApplicantPid(undefined);
      });
    return () => {
      cancelled = true;
    };
  }, [policyTargetKey]);

  return useMemo(() => {
    const samples = defaultSamplesForPolicy(policy, sampleApplicantPid);
    return samples.length > 0 ? samples : GENERIC_TEST_SAMPLES;
  }, [policy, sampleApplicantPid]);
}
