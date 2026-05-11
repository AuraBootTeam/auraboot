import { useState, useCallback } from 'react';
import {
  PlayIcon,
  CheckCircleIcon,
  XCircleIcon,
  ClockIcon,
  BeakerIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { ResultHelper } from '~/utils/type';

// ─── Types ────────────────────────────────────────────────────────────────────

interface PhaseEntry {
  phase: string;
  durationMs?: number;
  status: 'completed' | 'failed' | 'skipped';
}

interface ExecuteResult {
  commandCode: string;
  phaseReached: string;
  data: Record<string, unknown>;
  executionTimeMs: number;
  idempotentReplay?: boolean;
}

interface AuditLogEntry {
  id: number;
  commandCode: string;
  success: boolean;
  errorMessage?: string;
  executionTimeMs?: number;
  phaseReached: string;
  phaseTimings?: string | Record<string, number>;
  requestPayload?: string;
  executionResult?: string;
  createdAt: string;
}

const PIPELINE_PHASES = [
  'load',
  'schema_validate',
  'idempotency_check',
  'entitlement_check',
  'authorization',
  'sod_check',
  'state_check',
  'assert',
  'pre_invariant',
  'cross_field_validation',
  'pre_actions',
  'auto_set',
  'field_map',
  'computed_fields',
  'change_tracking',
  'handler',
  'side_effect',
  'roll_up',
  'post_action',
  'post_execution',
  'effect',
  'domain_event',
  'api_call',
  'webhook',
  'post_invariant',
  'governance_snapshot',
  'completion',
];

function parsePhaseTimings(value: AuditLogEntry['phaseTimings']): Record<string, number> {
  if (!value) return {};
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as Record<string, number>;
    } catch {
      return {};
    }
  }
  return value;
}

// ─── Phase Waterfall ──────────────────────────────────────────────────────────

function PhaseWaterfall({ phases }: { phases: PhaseEntry[] }) {
  const maxDuration = Math.max(...phases.map((p) => p.durationMs ?? 0), 1);

  return (
    <div className="space-y-1">
      {phases.map(({ phase, durationMs, status }) => (
        <div key={phase} className="flex items-center gap-2">
          <div className="w-32 text-right">
            <span
              className={`font-mono text-xs ${
                status === 'failed'
                  ? 'font-bold text-red-600'
                  : status === 'completed'
                    ? 'text-gray-700'
                    : 'text-gray-300'
              }`}
            >
              {phase}
            </span>
          </div>
          <div className="flex flex-1 items-center gap-2">
            {status !== 'skipped' && durationMs !== undefined ? (
              <>
                <div className="h-3 flex-1 overflow-hidden rounded-full bg-gray-100">
                  <div
                    className={`h-full rounded-full transition-all ${
                      status === 'failed' ? 'bg-red-400' : 'bg-blue-400'
                    }`}
                    style={{ width: `${Math.max(2, (durationMs / maxDuration) * 100)}%` }}
                  />
                </div>
                <span
                  className={`w-14 text-right text-xs ${
                    status === 'failed' ? 'text-red-600' : 'text-gray-500'
                  }`}
                >
                  {durationMs}ms
                </span>
              </>
            ) : status === 'skipped' ? (
              <span className="text-xs text-gray-300 italic">skipped</span>
            ) : (
              <div className="h-3 flex-1 rounded-full bg-gray-100" />
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Sample payloads ──────────────────────────────────────────────────────────

const SAMPLE_PAYLOADS: Record<string, string> = {
  create: JSON.stringify({ name: 'Test record', status: 'draft' }, null, 2),
  update: JSON.stringify({ targetRecordId: '1', name: 'Updated name' }, null, 2),
  delete: JSON.stringify({ targetRecordId: '1' }, null, 2),
};

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function CommandWorkbenchPage() {
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [commandCode, setCommandCode] = useState('');
  const [payload, setPayload] = useState('{\n  \n}');
  const [targetRecordId, setTargetRecordId] = useState('');
  const [running, setRunning] = useState(false);

  // Results
  const [result, setResult] = useState<ExecuteResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [auditLog, setAuditLog] = useState<AuditLogEntry | null>(null);
  const [payloadError, setPayloadError] = useState<string | null>(null);

  const validatePayload = (raw: string): Record<string, unknown> | null => {
    try {
      return JSON.parse(raw);
    } catch {
      setPayloadError(l('JSON 格式错误', 'Invalid JSON'));
      return null;
    }
  };

  const fetchLatestAuditLog = useCallback(async (code: string) => {
    try {
      const res = await fetch(
        `/api/meta/commands/audit-logs?commandCode=${encodeURIComponent(code)}&pageSize=1`,
      );
      const data = await res.json();
      if (ResultHelper.isSuccess(data) && data.data.records?.length > 0) {
        setAuditLog(data.data.records[0]);
      }
    } catch {
      // ignore
    }
  }, []);

  const handleRun = async () => {
    setPayloadError(null);
    setError(null);
    setResult(null);
    setAuditLog(null);

    const parsedPayload = validatePayload(payload);
    if (!parsedPayload) return;
    if (targetRecordId.trim()) {
      parsedPayload['targetRecordId'] = targetRecordId.trim();
    }

    setRunning(true);
    try {
      const res = await fetch(`/api/meta/commands/execute/${encodeURIComponent(commandCode)}`, {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ payload: parsedPayload }),
      });
      const data = await res.json();

      if (ResultHelper.isSuccess(data)) {
        setResult(data.data);
        // Fetch audit log to get phase timings (available after execution)
        setTimeout(() => fetchLatestAuditLog(commandCode), 300);
      } else {
        setError(data.message ?? data.desc ?? l('执行失败', 'Execution failed'));
        setTimeout(() => fetchLatestAuditLog(commandCode), 300);
      }
    } catch (err) {
      setError(String(err));
    } finally {
      setRunning(false);
    }
  };

  // Build phase waterfall from audit log
  const buildPhaseEntries = (): PhaseEntry[] => {
    if (!auditLog) return [];
    const timings = parsePhaseTimings(auditLog.phaseTimings);
    const reachedIndex = PIPELINE_PHASES.indexOf(auditLog.phaseReached);
    const lastTimedIndex = Math.max(
      -1,
      ...Object.keys(timings).map((phase) => PIPELINE_PHASES.indexOf(phase)),
    );
    const effectiveReachedIndex = Math.max(reachedIndex, lastTimedIndex);

    return PIPELINE_PHASES.map((phase, idx) => {
      if (idx > effectiveReachedIndex) return { phase, status: 'skipped' as const };
      const isFailed = !auditLog.success && idx === effectiveReachedIndex;
      return {
        phase,
        durationMs: timings[phase],
        status: isFailed ? ('failed' as const) : ('completed' as const),
      };
    });
  };

  const phaseEntries = buildPhaseEntries();
  const totalExecutionTime = auditLog?.executionTimeMs ?? result?.executionTimeMs;

  return (
    <div className="mx-auto max-w-5xl space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <BeakerIcon className="h-6 w-6 text-indigo-600" />
        <div>
          <h1 className="text-xl font-semibold text-gray-900">
            {l('命令测试工作台', 'Command Test Workbench')}
          </h1>
          <p className="text-sm text-gray-500">
            {l(
              '执行命令并查看 20 阶段管道执行轨迹',
              'Execute commands and visualize the 20-stage pipeline trace',
            )}
          </p>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-6">
        {/* ── Left: Input ── */}
        <div className="space-y-4">
          <h2 className="text-sm font-medium text-gray-700">{l('输入', 'Input')}</h2>

          {/* Command code */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-500">
              {l('命令 Code', 'Command Code')} <span className="text-red-500">*</span>
            </label>
            <input
              className="w-full rounded-lg border px-3 py-2 font-mono text-sm"
              placeholder={l('例如: crm_create_lead', 'e.g. crm_create_lead')}
              value={commandCode}
              onChange={(e) => setCommandCode(e.target.value)}
            />
          </div>

          {/* Target record ID */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-500">
              {l(
                '目标记录 ID（更新/删除/状态变更时填写）',
                'Target Record ID (for UPDATE/DELETE/STATE_TRANSITION)',
              )}
            </label>
            <input
              className="w-full rounded-lg border px-3 py-2 font-mono text-sm"
              placeholder={l('记录 ID', 'Record ID')}
              value={targetRecordId}
              onChange={(e) => setTargetRecordId(e.target.value)}
            />
          </div>

          {/* Payload */}
          <div>
            <div className="mb-1 flex items-center justify-between">
              <label className="text-xs font-medium text-gray-500">
                {l('Payload (JSON)', 'Payload (JSON)')}
              </label>
              <div className="flex gap-1">
                {Object.keys(SAMPLE_PAYLOADS).map((type) => (
                  <button
                    key={type}
                    onClick={() => setPayload(SAMPLE_PAYLOADS[type])}
                    className="text-xs text-indigo-600 hover:underline"
                  >
                    {type}
                  </button>
                ))}
              </div>
            </div>
            <textarea
              className={`h-48 w-full resize-none rounded-lg border px-3 py-2 font-mono text-sm ${
                payloadError ? 'border-red-400' : ''
              }`}
              value={payload}
              onChange={(e) => {
                setPayload(e.target.value);
                setPayloadError(null);
              }}
              spellCheck={false}
            />
            {payloadError && <p className="mt-0.5 text-xs text-red-500">{payloadError}</p>}
          </div>

          {/* Run button */}
          <button
            disabled={running || !commandCode.trim()}
            onClick={handleRun}
            className="flex w-full items-center justify-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {running ? (
              <>
                <ArrowAnimation />
                {l('执行中...', 'Running...')}
              </>
            ) : (
              <>
                <PlayIcon className="h-4 w-4" />
                {l('执行命令', 'Execute Command')}
              </>
            )}
          </button>
        </div>

        {/* ── Right: Results ── */}
        <div className="space-y-4">
          <h2 className="text-sm font-medium text-gray-700">{l('执行结果', 'Result')}</h2>

          {!result && !error && !running && (
            <div className="py-16 text-center text-sm text-gray-300">
              {l('执行命令后在此查看结果', 'Execute a command to see results here')}
            </div>
          )}

          {running && (
            <div className="py-16 text-center text-sm text-gray-400">
              <ArrowAnimation large />
              <p className="mt-2">{l('正在执行...', 'Executing...')}</p>
            </div>
          )}

          {/* Success result */}
          {result && !error && (
            <div className="space-y-3">
              <div className="flex items-center gap-2 rounded-lg border border-green-200 bg-green-50 p-3">
                <CheckCircleIcon className="h-5 w-5 text-green-500" />
                <div>
                  <p className="text-sm font-medium text-green-700">
                    {l('执行成功', 'Execution Successful')}
                  </p>
                  {totalExecutionTime !== undefined && (
                    <p className="text-xs text-green-600">
                      <ClockIcon className="mr-0.5 inline h-3 w-3" />
                      {totalExecutionTime}ms
                    </p>
                  )}
                </div>
              </div>
              {result.data && Object.keys(result.data).length > 0 && (
                <div>
                  <p className="mb-1 text-xs font-medium text-gray-500">
                    {l('返回数据', 'Return Data')}
                  </p>
                  <pre className="max-h-32 overflow-auto rounded-lg bg-gray-50 p-3 font-mono text-xs">
                    {JSON.stringify(result.data, null, 2)}
                  </pre>
                </div>
              )}
            </div>
          )}

          {/* Error result */}
          {error && (
            <div className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-3">
              <XCircleIcon className="mt-0.5 h-5 w-5 flex-shrink-0 text-red-500" />
              <div>
                <p className="text-sm font-medium text-red-700">
                  {l('执行失败', 'Execution Failed')}
                </p>
                <p className="mt-1 font-mono text-xs text-red-600">{error}</p>
              </div>
            </div>
          )}

          {/* Phase waterfall */}
          {phaseEntries.length > 0 && (
            <div>
              <div className="mb-2 flex items-center justify-between">
                <p className="text-xs font-medium text-gray-500">
                  {l('阶段耗时瀑布图', 'Phase Timing Waterfall')}
                </p>
                {totalExecutionTime !== undefined && (
                  <span className="text-xs text-gray-400">
                    {l('总耗时', 'Total')}: {totalExecutionTime}ms
                  </span>
                )}
              </div>
              <div className="rounded-lg bg-gray-50 p-3">
                <PhaseWaterfall phases={phaseEntries} />
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function ArrowAnimation({ large }: { large?: boolean }) {
  return (
    <svg
      className={`animate-spin ${large ? 'mx-auto h-8 w-8' : 'h-4 w-4'} text-current`}
      fill="none"
      viewBox="0 0 24 24"
    >
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path
        className="opacity-75"
        fill="currentColor"
        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
      />
    </svg>
  );
}
