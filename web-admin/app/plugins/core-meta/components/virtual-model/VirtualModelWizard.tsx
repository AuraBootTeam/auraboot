import { useState } from 'react';
import { SourceTypeStep } from './SourceTypeStep';
import { SourceRefStep } from './SourceRefStep';
import { SchemaDetectionStep } from './SchemaDetectionStep';
import { CapabilitiesStep } from './CapabilitiesStep';
import { MetaInfoStep } from './MetaInfoStep';
import { submitVirtualModel } from './submitVirtualModel';

export type VirtualSourceType = 'namedQuery' | 'endpoint' | 'sqlView';

export interface DetectedField {
  code: string;
  dataType: string;
  isPrimary?: boolean;
  sortable?: boolean;
  filterable?: boolean;
}

export interface EndpointChannelConfig {
  endpoint?: string;
  method?: string;
  responseItemsPath?: string;
  responseTotalPath?: string;
  responseItemPath?: string;
  pageParam?: string;
  pageSizeParam?: string;
  sortFieldParam?: string;
  sortOrderParam?: string;
  filterParamMode?: string;
  pathParams?: string[];
}

export interface WizardState {
  sourceType?: VirtualSourceType;
  sourceRef?: string;
  endpointAdapter?: { list?: EndpointChannelConfig; detail?: EndpointChannelConfig };
  detectedFields?: DetectedField[];
  primaryKey?: string;
  capabilities?: {
    list?: boolean;
    detail?: boolean;
    sort?: boolean;
    filter?: boolean;
    paginate?: boolean;
    export?: boolean;
  };
  meta?: { code?: string; displayName?: string; description?: string };
}

export interface VirtualModelWizardProps {
  onComplete: (pid: string) => void;
  onCancel: () => void;
}

const STEPS = [
  { id: 1, title: '选择类型', component: SourceTypeStep },
  { id: 2, title: '数据源', component: SourceRefStep },
  { id: 3, title: '检测字段', component: SchemaDetectionStep },
  { id: 4, title: '能力声明', component: CapabilitiesStep },
  { id: 5, title: '元信息', component: MetaInfoStep },
] as const;

export function VirtualModelWizard({ onComplete, onCancel }: VirtualModelWizardProps) {
  const [state, setState] = useState<WizardState>({});
  const [current, setCurrent] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | undefined>();

  const canAdvance = () => {
    switch (current) {
      case 1:
        return !!state.sourceType;
      case 2:
        if (state.sourceType === 'endpoint') {
          return !!state.endpointAdapter?.list?.endpoint;
        }
        return !!(state.sourceRef && state.sourceRef.trim());
      case 3:
        return !!(state.detectedFields && state.detectedFields.length > 0 && state.primaryKey);
      case 4:
        return true;
      case 5:
        return !!(state.meta?.code && state.meta?.displayName);
      default:
        return false;
    }
  };

  const StepComponent = STEPS[current - 1].component as React.ComponentType<{
    state: WizardState;
    setState: React.Dispatch<React.SetStateAction<WizardState>>;
  }>;

  const handleSubmit = async () => {
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      const pid = await submitVirtualModel(state);
      onComplete(pid);
    } catch (e) {
      setSubmitError(String(e instanceof Error ? e.message : e));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="flex min-h-screen bg-white">
      <aside className="w-64 border-r bg-gray-50 p-6">
        <h2 className="mb-6 text-lg font-semibold">新建虚拟 Model</h2>
        <ol className="space-y-2">
          {STEPS.map((s) => (
            <li key={s.id}>
              <button
                className={`w-full rounded px-3 py-2 text-left text-sm transition-colors ${
                  s.id === current
                    ? 'bg-blue-100 font-medium text-blue-700'
                    : s.id < current
                    ? 'text-green-700'
                    : 'text-gray-500'
                }`}
                onClick={() => s.id < current && setCurrent(s.id)}
                disabled={s.id > current}
                data-testid={`wizard-step-${s.id}`}
              >
                <span className="mr-2">{s.id < current ? '✓' : s.id}</span>
                {s.title}
              </button>
            </li>
          ))}
        </ol>
      </aside>

      <main className="flex-1 overflow-auto p-8">
        <div className="mx-auto max-w-3xl">
          <StepComponent state={state} setState={setState} />

          {submitError && (
            <div
              className="mt-6 rounded bg-red-50 p-4 text-sm text-red-700"
              data-testid="wizard-submit-error"
            >
              保存失败: {submitError}
            </div>
          )}

          <div className="mt-8 flex justify-between border-t pt-6">
            <button
              onClick={onCancel}
              className="text-sm text-gray-500 hover:text-gray-700"
              data-testid="wizard-cancel"
            >
              取消
            </button>
            <div className="flex gap-3">
              {current > 1 && (
                <button
                  onClick={() => setCurrent((c) => c - 1)}
                  className="rounded border px-4 py-2 text-sm hover:bg-gray-50"
                  data-testid="wizard-prev"
                >
                  上一步
                </button>
              )}
              {current < 5 && (
                <button
                  onClick={() => setCurrent((c) => c + 1)}
                  disabled={!canAdvance()}
                  className="rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                  data-testid="wizard-next"
                >
                  下一步
                </button>
              )}
              {current === 5 && (
                <button
                  onClick={handleSubmit}
                  disabled={!canAdvance() || submitting}
                  className="rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                  data-testid="wizard-submit"
                >
                  {submitting ? '保存中...' : '保存'}
                </button>
              )}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
