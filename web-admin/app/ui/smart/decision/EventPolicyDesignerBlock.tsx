import { useEffect, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionApi,
  type EventPolicySummary,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';
import { EventPolicyDesignerWorkflow } from '~/shared/decision/ui/EventPolicyDesignerWorkflow';
import type { TestSample } from '~/shared/decision/ui/ConditionTestRunPanel';
import type { FieldOption } from '~/shared/decision/ui/ConditionBuilder';
import {
  factCatalogToFieldOptions,
  modelFieldsToFieldOptions,
} from '~/shared/decision/ui/factCatalogAdapter';

interface EventPolicyDesignerBlockProps {
  block?: {
    props?: {
      policyCode?: string;
      fields?: FieldOption[];
    };
    policyCode?: string;
    fields?: FieldOption[];
  };
  runtime?: {
    getContext?: () => {
      record?: Record<string, unknown>;
      row?: Record<string, unknown>;
      data?: Record<string, unknown>;
    };
  };
}

type UserOption = {
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
};

const DEFAULT_FIELDS: FieldOption[] = [
  { scope: 'event', path: 'type', label: '事件类型', dataType: 'string' },
  {
    scope: 'record',
    path: 'data.priority',
    label: '优先级',
    dataType: 'enum',
    options: ['HIGH', 'NORMAL', 'LOW'],
  },
  { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
  { scope: 'record', path: 'data.status', label: '状态', dataType: 'string' },
];
const LEAVE_REQUEST_FIELDS: FieldOption[] = [
  { scope: 'event', path: 'type', label: '事件类型', dataType: 'string' },
  { scope: 'record', path: 'data.wd_req_days', label: '请假天数', dataType: 'decimal' },
  { scope: 'record', path: 'data.wd_req_applicant', label: '申请人', dataType: 'user' },
  { scope: 'record', path: 'data.wd_req_no', label: '申请编号', dataType: 'string' },
  { scope: 'record', path: 'pid', label: '申请记录', dataType: 'string' },
  { scope: 'actor', path: 'roles', label: '触发人角色', dataType: 'collection' },
];

function createApi(): DecisionApi {
  const service = getApiService();
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) =>
      service.get<T>(endpoint, params),
    post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
    delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
  };
  return createDecisionApi(http);
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function asPolicyList(raw: unknown): EventPolicySummary[] {
  if (Array.isArray(raw)) return raw as EventPolicySummary[];
  if (raw && typeof raw === 'object') {
    const record = raw as Record<string, unknown>;
    if (Array.isArray(record.records)) return record.records as EventPolicySummary[];
    if (Array.isArray(record.data)) return record.data as EventPolicySummary[];
  }
  return [];
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

function runtimeRecord(runtime: EventPolicyDesignerBlockProps['runtime']): Record<string, unknown> {
  const context = runtime?.getContext?.();
  return context?.record ?? context?.row ?? context?.data ?? {};
}

function mergeFieldOptions(primary: FieldOption[], fallback: FieldOption[]): FieldOption[] {
  const seen = new Set<string>();
  return [...primary, ...fallback].filter((field) => {
    const key = `${field.scope}:${field.path}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function defaultFieldsForPolicy(policy: EventPolicySummary | null): FieldOption[] {
  if (policy?.policyCode === 'leave_request_event_policy' || policy?.targetKey === 'wd_leave_request') {
    return LEAVE_REQUEST_FIELDS;
  }
  return DEFAULT_FIELDS;
}

function fieldsForPolicy(policy: EventPolicySummary | null, catalogFields: FieldOption[]): FieldOption[] {
  const targetKey = policy?.targetKey;
  if (!targetKey) return catalogFields;
  return catalogFields.filter((field) => {
    if (field.scope !== 'record') return true;
    if (!field.modelCode) return true;
    return field.modelCode === targetKey;
  });
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

function defaultSamplesForPolicy(
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

  const targetKey = policy.targetKey || 'record';
  const recordPid = `TEST-${policy.policyCode || targetKey}`;
  return [
    {
      label: '默认样例',
      context: {
        event: {
          type: policy.eventType,
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
    },
  ];
}

export function EventPolicyDesignerBlock({ block, runtime }: EventPolicyDesignerBlockProps) {
  const [searchParams] = useSearchParams();
  const params = useParams();
  const platformApi = useMemo(() => getApiService(), []);
  const api = useMemo(() => createApi(), []);
  const record = runtimeRecord(runtime);
  const configuredFields = block?.props?.fields ?? block?.fields;
  const policyCode =
    searchParams.get('policyCode') ??
    stringValue(block?.props?.policyCode) ??
    stringValue(block?.policyCode) ??
    stringValue(record.policyCode) ??
    stringValue(record.policy_code) ??
    stringValue(params.recordPid);
  const [catalogFields, setCatalogFields] = useState<FieldOption[]>([]);
  const [sampleApplicantPid, setSampleApplicantPid] = useState<string | undefined>();
  const [policy, setPolicy] = useState<EventPolicySummary | null>(null);
  const policyTargetKey = policy?.targetKey;
  const fields = configuredFields
    ?? mergeFieldOptions(fieldsForPolicy(policy, catalogFields), defaultFieldsForPolicy(policy));
  const samples = useMemo(() => defaultSamplesForPolicy(policy, sampleApplicantPid), [
    policy,
    sampleApplicantPid,
  ]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (configuredFields && configuredFields.length > 0) {
      setCatalogFields([]);
      return;
    }
    if (policyCode && !policy) {
      setCatalogFields([]);
      return;
    }
    let cancelled = false;
    const loadFields = async () => {
      try {
        const factFields = factCatalogToFieldOptions(await api.getFactCatalog(policyTargetKey));
        if (cancelled) return;
        if (factFields.length > 0) {
          setCatalogFields(factFields);
          return;
        }
      } catch {
        // Old runtimes may not expose the unified fact catalog yet; keep the legacy field index as fallback.
      }
      try {
        const rows = await api.getModelFields();
        if (cancelled) return;
        setCatalogFields(modelFieldsToFieldOptions(rows));
      } catch {
        if (!cancelled) setCatalogFields([]);
      }
    };
    loadFields();
    return () => {
      cancelled = true;
    };
  }, [api, configuredFields, policy, policyCode, policyTargetKey]);

  useEffect(() => {
    if (policyTargetKey !== 'wd_leave_request') {
      setSampleApplicantPid(undefined);
      return;
    }
    let cancelled = false;
    platformApi
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
  }, [platformApi, policyTargetKey]);

  useEffect(() => {
    if (!policyCode) {
      setPolicy(null);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError('');
    api
      .listPolicies({ keyword: policyCode })
      .then((raw) => {
        if (cancelled) return;
        const policies = asPolicyList(raw);
        const selected =
          policies.find((candidate) => candidate.policyCode === policyCode) ??
          ({ policyCode } as EventPolicySummary);
        setPolicy(selected);
      })
      .catch((e) => {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : String(e));
          setPolicy({ policyCode } as EventPolicySummary);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [api, policyCode]);

  if (!policyCode) {
    return (
      <section className="decisionops-list-page" data-testid="event-policy-designer-block">
        <div className="decisionops-state is-error">缺少 policyCode</div>
      </section>
    );
  }

  return (
    <section className="decisionops-list-page" data-testid="event-policy-designer-block">
      {loading && <div className="decisionops-state">加载策略...</div>}
      {error && (
        <div className="decisionops-state is-error" data-testid="epd-block-error">
          {error}
        </div>
      )}
      {policy && <EventPolicyDesignerWorkflow api={api} fields={fields} selectedPolicy={policy} samples={samples} />}
    </section>
  );
}

export default EventPolicyDesignerBlock;
