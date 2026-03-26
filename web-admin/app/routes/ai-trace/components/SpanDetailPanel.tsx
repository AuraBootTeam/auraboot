import { useState } from 'react';
import { JsonViewer } from './JsonViewer';

interface Span {
  spanId: string;
  type: string;
  name: string;
  status: string;
  level: string | null;
  statusMessage: string | null;
  durationMs: number | null;
  model: string | null;
  modelParameters: Record<string, any> | null;
  inputTokens: number | null;
  outputTokens: number | null;
  cost: number | null;
  stopReason: string | null;
  completionStartTime: string | null;
  input: any;
  output: any;
  toolDefinitions: any;
  toolCalls: any;
  metadata: Record<string, any> | null;
  startTime: string;
  endTime: string | null;
}

interface Props {
  span: Span;
}

type TabKey = 'info' | 'input' | 'output' | 'tools' | 'metadata';

const STATUS_COLORS: Record<string, string> = {
  success: 'text-green-600 dark:text-green-400',
  confirmed: 'text-green-600 dark:text-green-400',
  ERROR: 'text-red-600 dark:text-red-400',
  pending: 'text-amber-600 dark:text-amber-400',
  in_progress: 'text-blue-600 dark:text-blue-400',
  cancelled: 'text-gray-500 dark:text-gray-400',
};

export function SpanDetailPanel({ span }: Props) {
  const hasTools = span.toolDefinitions != null || span.toolCalls != null;

  const tabs: { key: TabKey; label: string; show: boolean }[] = [
    { key: 'info', label: 'Info', show: true },
    { key: 'input', label: 'Input', show: true },
    { key: 'output', label: 'Output', show: true },
    { key: 'tools', label: 'Tools', show: hasTools },
    { key: 'metadata', label: 'Meta', show: true },
  ];

  const visibleTabs = tabs.filter((t) => t.show);
  const [tab, setTab] = useState<TabKey>('info');

  // Time to first token
  const ttft =
    span.completionStartTime && span.startTime
      ? new Date(span.completionStartTime).getTime() - new Date(span.startTime).getTime()
      : null;

  return (
    <div
      className="flex h-full flex-col overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700"
      data-testid="span-detail-panel"
    >
      {/* Header */}
      <div className="border-b border-gray-200 bg-gray-50 px-4 py-3 dark:border-gray-700 dark:bg-gray-800">
        <div className="truncate font-mono text-sm font-medium text-gray-900 dark:text-white">
          {span.name}
        </div>
        <div className="mt-1 flex items-center gap-2">
          <span className="rounded bg-gray-200 px-1.5 py-0.5 text-xs text-gray-600 dark:bg-gray-700 dark:text-gray-300">
            {span.type}
          </span>
          <span className={`text-xs font-medium ${STATUS_COLORS[span.status] || 'text-gray-500'}`}>
            {span.status}
          </span>
          {span.model && (
            <span className="font-mono text-xs text-gray-500 dark:text-gray-400">{span.model}</span>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        {visibleTabs.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={`border-b-2 px-4 py-2 text-xs font-medium transition-colors ${
              tab === t.key
                ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto bg-white p-4 dark:bg-gray-800/50">
        {tab === 'info' && (
          <dl className="space-y-3 text-sm">
            <Row label="Span ID" value={span.spanId} mono />
            <Row label="Type" value={span.type} />
            <Row label="Status" value={span.status} valueClass={STATUS_COLORS[span.status]} />
            {span.level && <Row label="Level" value={span.level} />}
            {span.statusMessage && <Row label="Message" value={span.statusMessage} />}
            <Row label="Duration" value={span.durationMs != null ? `${span.durationMs}ms` : '-'} />
            {ttft != null && <Row label="Time to First Token" value={`${ttft}ms`} />}
            <Row label="Start" value={new Date(span.startTime).toLocaleString()} />
            {span.endTime && <Row label="End" value={new Date(span.endTime).toLocaleString()} />}
            {span.model && <Row label="Model" value={span.model} mono />}
            {span.inputTokens != null && (
              <Row label="Input Tokens" value={span.inputTokens.toLocaleString()} />
            )}
            {span.outputTokens != null && (
              <Row label="Output Tokens" value={span.outputTokens.toLocaleString()} />
            )}
            {span.cost != null && span.cost > 0 && (
              <Row label="Cost" value={`$${Number(span.cost).toFixed(6)}`} />
            )}
            {span.stopReason && <Row label="Stop Reason" value={span.stopReason} />}
            {span.modelParameters && Object.keys(span.modelParameters).length > 0 && (
              <div>
                <dt className="mb-1 text-xs text-gray-500 dark:text-gray-400">Model Parameters</dt>
                <JsonViewer data={span.modelParameters} maxHeight="150px" />
              </div>
            )}
          </dl>
        )}
        {tab === 'input' && <JsonViewer data={span.input} />}
        {tab === 'output' && <JsonViewer data={span.output} />}
        {tab === 'tools' && (
          <div className="space-y-4">
            {span.toolCalls && (
              <div>
                <div className="mb-2 text-xs font-medium text-gray-500 dark:text-gray-400">
                  Tool Calls
                </div>
                <JsonViewer data={span.toolCalls} />
              </div>
            )}
            {span.toolDefinitions && (
              <div>
                <div className="mb-2 text-xs font-medium text-gray-500 dark:text-gray-400">
                  Tool Definitions
                </div>
                <JsonViewer data={span.toolDefinitions} />
              </div>
            )}
          </div>
        )}
        {tab === 'metadata' && <JsonViewer data={span.metadata} />}
      </div>
    </div>
  );
}

function Row({
  label,
  value,
  mono,
  valueClass,
}: {
  label: string;
  value: string;
  mono?: boolean;
  valueClass?: string;
}) {
  return (
    <div className="flex">
      <dt className="w-32 shrink-0 text-xs text-gray-500 dark:text-gray-400">{label}</dt>
      <dd
        className={`text-sm break-all text-gray-900 dark:text-gray-100 ${mono ? 'font-mono text-xs' : ''} ${valueClass || ''}`}
      >
        {value}
      </dd>
    </div>
  );
}
