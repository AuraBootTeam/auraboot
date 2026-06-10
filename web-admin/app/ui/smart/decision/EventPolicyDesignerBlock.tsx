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
import type { FieldOption } from '~/shared/decision/ui/ConditionBuilder';

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

const DEFAULT_FIELDS: FieldOption[] = [
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

function runtimeRecord(runtime: EventPolicyDesignerBlockProps['runtime']): Record<string, unknown> {
  const context = runtime?.getContext?.();
  return context?.record ?? context?.row ?? context?.data ?? {};
}

export function EventPolicyDesignerBlock({ block, runtime }: EventPolicyDesignerBlockProps) {
  const [searchParams] = useSearchParams();
  const params = useParams();
  const api = useMemo(() => createApi(), []);
  const record = runtimeRecord(runtime);
  const policyCode =
    searchParams.get('policyCode') ??
    stringValue(block?.props?.policyCode) ??
    stringValue(block?.policyCode) ??
    stringValue(record.policyCode) ??
    stringValue(record.policy_code) ??
    stringValue(params.recordId);
  const fields = block?.props?.fields ?? block?.fields ?? DEFAULT_FIELDS;
  const [policy, setPolicy] = useState<EventPolicySummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

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
      {policy && <EventPolicyDesignerWorkflow api={api} fields={fields} selectedPolicy={policy} />}
    </section>
  );
}

export default EventPolicyDesignerBlock;
