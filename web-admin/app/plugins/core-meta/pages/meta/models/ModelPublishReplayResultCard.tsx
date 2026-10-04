import { Link } from 'react-router';
import type { ModelPublishReplayResult } from '~/shared/services/modelService';
import { getPublishText } from './modelPublishSampleContext';
import { getReplayStatusLabel, getReplayResultMessage, getReplayErrorMessage } from './modelPublishReplayMessages';
import { formatReplayOutputLabel, formatReplayOutputValue, getReplayConsumerLabel,
  getReplayStatusClass, hasReplayOutputLabel, isTechnicalReplayOutput, permissionReplayTraceHref, replayText } from './modelPublishReplayPresentation';

export function ModelPublishReplayResultCard({ result, locale }: { result: ModelPublishReplayResult; locale: string }) {
  const step = result.step;
  const consumer = getReplayConsumerLabel(step?.consumerType, locale);
  const message = getReplayResultMessage(result, locale);
  const traceHref = permissionReplayTraceHref(result);
  const outputs = Object.entries(result.outputs ?? {}).filter(([key]) => key !== 'steps');
  const candidates = new Set(['candidateUserIds', 'candidateGroupIds']);
  const primary = outputs.filter(([key]) => !isTechnicalReplayOutput(key) || candidates.has(key));
  const technical = outputs.filter(([key]) => isTechnicalReplayOutput(key) || !hasReplayOutputLabel(key));
  const identities = [['sourceCode', step?.sourceCode], ['sourcePid', step?.sourcePid], ['traceId', result.traceId]] as const;
  return (
    <div data-testid={'model-publish-replay-result-' + (step?.consumerType || 'unknown')}
      className="rounded border border-blue-100 bg-white p-3">
      <div data-testid="replay-business-summary">
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded bg-gray-100 px-2 py-1 text-xs font-medium text-gray-700">{consumer}</span>
          <span className={'rounded px-2 py-1 text-xs font-medium ' + getReplayStatusClass(result.status)}>
            {getReplayStatusLabel(result.status, locale)}
          </span>
          <span className="text-sm font-medium text-gray-900">{step?.sourceName || consumer}</span>
          {traceHref && <Link to={traceHref} data-testid="model-publish-replay-open-permission-trace"
            className="rounded border border-blue-200 px-2 py-1 text-xs font-medium text-blue-700 hover:bg-blue-50">
            {getPublishText('openTrace', locale)}
          </Link>}
        </div>
        {message && <p className="mt-2 text-sm text-gray-700">{message}</p>}
        {primary.length > 0 && <div className="mt-2 flex flex-wrap gap-2 text-xs text-gray-600">
          {primary.map(([key, value]) => <span key={key} className="rounded bg-gray-100 px-2 py-1">
            {formatReplayOutputLabel(key, locale)}: {formatReplayOutputValue(key, value, locale)}
          </span>)}
        </div>}
        {result.errors?.length ? <ul className="mt-2 list-inside list-disc text-sm text-red-700">
          {result.errors.map((error, index) => <li key={index}>{getReplayErrorMessage(error, result, locale)}</li>)}
        </ul> : null}
      </div>
      {(technical.length > 0 || identities.some(([, value]) => value)) &&
        <details data-testid="replay-technical-details" className="mt-3 text-xs text-gray-600">
          <summary className="cursor-pointer font-medium">{replayText('technicalDetails', locale)}</summary>
          <dl className="mt-2 space-y-1 break-all">
            {identities.filter(([, value]) => value).map(([key, value]) => <div key={key}>
              <dt className="font-medium">{replayText(key, locale)}</dt><dd>{value}</dd>
            </div>)}
            {technical.map(([key, value]) => <div key={key}>
              <dt className="font-medium">{hasReplayOutputLabel(key) ? formatReplayOutputLabel(key, locale) : key}</dt>
              <dd>{candidates.has(key) && Array.isArray(value)
                ? value.map(String).join(', ') || formatReplayOutputValue(key, value, locale)
                : formatReplayOutputValue(key, value, locale)}</dd>
            </div>)}
          </dl>
        </details>}
    </div>
  );
}
