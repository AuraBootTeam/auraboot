import { useCallback, useEffect, useState } from 'react';
import { ArrowPathIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { openPlatformApiFetch as apiFetch } from './open-platform-api';

interface Overview {
  windowHours: number;
  totalCalls: number;
  errorCalls: number;
  throttledCalls: number;
  errorRate: number;
  p95DurationMs: number;
  deadLetterCount: number;
}
interface Audit {
  requestId: string;
  method: string;
  path: string;
  status: number;
  durationMs: number;
  occurredAt: string;
}
interface Delivery {
  pid: string;
  subscriptionName?: string;
  eventId: string;
  status: string;
  retryCount: number;
  maxRetries: number;
  responseStatus?: number;
  failureReason?: string;
  createdAt: string;
  replayable: boolean;
}
interface EventDescriptor {
  type: string;
  currentVersion: number;
  supportedVersions: number[];
  classification: string;
  subjectResourceCode: string;
}
interface WebhookHealth {
  pid: string;
  name: string;
  eventType: string;
  eventVersion: number;
  catalogCurrentVersion?: number;
  compatible: boolean;
  rotationStatus: 'healthy' | 'due' | 'overdue' | 'missing';
  rotationDueAt?: string;
  enabled: boolean;
}

export default function OpenPlatformOperationsPanel({
  installationPid,
}: {
  installationPid: string;
}) {
  const { t } = useI18n();
  const { showToast } = useToastContext();
  const [overview, setOverview] = useState<Overview | null>(null);
  const [audits, setAudits] = useState<Audit[]>([]);
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [eventCatalog, setEventCatalog] = useState<EventDescriptor[]>([]);
  const [webhookHealth, setWebhookHealth] = useState<WebhookHealth[]>([]);
  const [requestId, setRequestId] = useState('');
  const [deliveryStatus, setDeliveryStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingReplay, setPendingReplay] = useState<Delivery | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const auditQuery = requestId.trim()
        ? `?requestId=${encodeURIComponent(requestId.trim())}`
        : '';
      const deliveryQuery = deliveryStatus ? `?status=${encodeURIComponent(deliveryStatus)}` : '';
      const [overviewData, auditData, deliveryData, catalogData, healthData] = await Promise.all([
        apiFetch<Overview>(
          `/api/open-platform/installations/${installationPid}/overview?windowHours=24`,
        ),
        apiFetch<Audit[]>(
          `/api/open-platform/installations/${installationPid}/audits${auditQuery}`,
        ),
        apiFetch<Delivery[]>(
          `/api/open-platform/installations/${installationPid}/webhook-deliveries${deliveryQuery}`,
        ),
        apiFetch<EventDescriptor[]>('/api/open-platform/event-catalog'),
        apiFetch<WebhookHealth[]>(
          `/api/open-platform/installations/${installationPid}/webhook-health`,
        ),
      ]);
      setOverview(overviewData);
      setAudits(auditData ?? []);
      setDeliveries(deliveryData ?? []);
      setEventCatalog(catalogData ?? []);
      setWebhookHealth(healthData ?? []);
    } catch (loadError) {
      setError(
        loadError instanceof Error
          ? loadError.message
          : t('openPlatform.operationsLoadFailed', undefined, 'Unable to load operations data'),
      );
    } finally {
      setLoading(false);
    }
  }, [deliveryStatus, installationPid, requestId, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const replay = async () => {
    if (!pendingReplay) return;
    try {
      await apiFetch<void>(
        `/api/open-platform/installations/${installationPid}/webhook-deliveries/${pendingReplay.pid}/replay`,
        { method: 'POST' },
      );
      setPendingReplay(null);
      showToast(t('openPlatform.replayQueued', undefined, 'Webhook replay queued'), 'success');
      await load();
    } catch (replayError) {
      showToast(
        replayError instanceof Error
          ? replayError.message
          : t('openPlatform.replayFailed', undefined, 'Unable to replay webhook'),
        'error',
      );
    }
  };

  return (
    <section
      className="mt-4 min-w-0 space-y-4 border-t border-gray-100 pt-4 dark:border-gray-800"
      data-testid="open-platform-operations-panel"
    >
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold text-gray-900 dark:text-white">
            {t('openPlatform.operationsTitle', undefined, 'Operations')}
          </h3>
          <p className="text-xs text-gray-500">
            {t(
              'openPlatform.operationsSubtitle',
              undefined,
              'Last 24 hours of API traffic and outbound delivery health.',
            )}
          </p>
        </div>
        <button
          type="button"
          onClick={() => void load()}
          className="rounded p-2 text-gray-500 hover:bg-gray-100"
          aria-label={t('openPlatform.refresh', undefined, 'Refresh')}
          data-testid="open-platform-operations-refresh"
        >
          <ArrowPathIcon className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
        </button>
      </div>
      {error ? (
        <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          <p>{error}</p>
          <button type="button" onClick={() => void load()} className="mt-2 font-medium">
            {t('openPlatform.retry', undefined, 'Retry')}
          </button>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-2 lg:grid-cols-6">
            {[
              [t('openPlatform.totalCalls', undefined, 'Calls'), overview?.totalCalls ?? '—'],
              [t('openPlatform.errorCalls', undefined, 'Errors'), overview?.errorCalls ?? '—'],
              [
                t('openPlatform.errorRate', undefined, 'Error rate'),
                overview ? `${(overview.errorRate * 100).toFixed(2)}%` : '—',
              ],
              ['P95', overview ? `${Math.round(overview.p95DurationMs)} ms` : '—'],
              ['HTTP 429', overview?.throttledCalls ?? '—'],
              [
                t('openPlatform.deadLetters', undefined, 'Dead letters'),
                overview?.deadLetterCount ?? '—',
              ],
            ].map(([label, value]) => (
              <div key={label} className="rounded-lg bg-slate-50 p-3 dark:bg-slate-950">
                <div className="text-[11px] tracking-wide text-gray-400 uppercase">{label}</div>
                <div className="mt-1 text-lg font-semibold text-gray-900 dark:text-white">
                  {value}
                </div>
              </div>
            ))}
          </div>
          <div className="grid min-w-0 gap-4 lg:grid-cols-2">
            <div
              data-testid="open-platform-call-audit"
              className="min-w-0 overflow-hidden rounded-lg border border-gray-200 p-3 dark:border-gray-700"
            >
              <div className="mb-3 flex items-end gap-2">
                <label className="min-w-0 flex-1 text-xs font-medium text-gray-500">
                  request_id
                  <input
                    value={requestId}
                    onChange={(event) => setRequestId(event.target.value)}
                    placeholder="req_…"
                    className="mt-1 max-w-full min-w-0 rounded-md border border-gray-300 bg-white px-2 py-1.5 text-sm dark:border-gray-700 dark:bg-gray-950"
                  />
                </label>
              </div>
              <h4 className="mb-2 text-xs font-semibold tracking-wide text-gray-500 uppercase">
                {t('openPlatform.callAudit', undefined, 'Call audit')}
              </h4>
              {audits.length === 0 ? (
                <Empty label={t('openPlatform.noCalls', undefined, 'No calls match this filter')} />
              ) : (
                <div className="max-h-72 min-w-0 space-y-2 overflow-auto">
                  {audits.map((audit) => (
                    <div
                      key={`${audit.requestId}-${audit.occurredAt}`}
                      className="rounded-md bg-gray-50 p-2 text-xs dark:bg-gray-950"
                    >
                      <div className="flex min-w-0 justify-between gap-2">
                        <code className="min-w-0 truncate">
                          {audit.method} {audit.path}
                        </code>
                        <span className={audit.status >= 400 ? 'text-red-600' : 'text-emerald-600'}>
                          {audit.status}
                        </span>
                      </div>
                      <div className="mt-1 flex min-w-0 flex-col gap-1 text-gray-400 sm:flex-row sm:justify-between">
                        <code className="min-w-0 break-all">{audit.requestId}</code>
                        <span>
                          {formatTime(audit.occurredAt)} · {audit.durationMs} ms
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div className="min-w-0 overflow-hidden rounded-lg border border-gray-200 p-3 dark:border-gray-700">
              <div className="mb-3">
                <label className="text-xs font-medium text-gray-500">
                  {t('openPlatform.deliveryStatus', undefined, 'Delivery status')}
                  <select
                    value={deliveryStatus}
                    onChange={(event) => setDeliveryStatus(event.target.value)}
                    className="ml-2 rounded-md border border-gray-300 bg-white px-2 py-1.5 text-sm dark:border-gray-700 dark:bg-gray-950"
                  >
                    <option value="">{t('common.all', undefined, 'All')}</option>
                    <option value="dead_letter">dead_letter</option>
                    <option value="failed">failed</option>
                    <option value="pending">pending</option>
                    <option value="success">success</option>
                  </select>
                </label>
              </div>
              <h4 className="mb-2 text-xs font-semibold tracking-wide text-gray-500 uppercase">
                {t('openPlatform.webhookDeliveries', undefined, 'Webhook deliveries')}
              </h4>
              {deliveries.length === 0 ? (
                <Empty
                  label={t(
                    'openPlatform.noDeliveries',
                    undefined,
                    'No deliveries match this filter',
                  )}
                />
              ) : (
                <div className="max-h-72 space-y-2 overflow-auto">
                  {deliveries.map((delivery) => (
                    <div
                      key={delivery.pid}
                      className="rounded-md bg-gray-50 p-2 text-xs dark:bg-gray-950"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium">
                          {delivery.subscriptionName ||
                            t(
                              'openPlatform.deletedSubscription',
                              undefined,
                              'Deleted subscription',
                            )}
                        </span>
                        <code>{delivery.status}</code>
                      </div>
                      <div className="mt-1 text-gray-400">
                        {delivery.eventId} · {delivery.retryCount}/{delivery.maxRetries}
                        {delivery.responseStatus ? ` · HTTP ${delivery.responseStatus}` : ''}
                      </div>
                      <div className="mt-1 text-gray-400">{formatTime(delivery.createdAt)}</div>
                      {delivery.failureReason && (
                        <p className="mt-1 line-clamp-2 text-red-600">{delivery.failureReason}</p>
                      )}
                      {delivery.replayable && (
                        <button
                          type="button"
                          onClick={() => setPendingReplay(delivery)}
                          className="mt-2 rounded border border-amber-300 px-2 py-1 font-medium text-amber-700 hover:bg-amber-50"
                        >
                          {t('openPlatform.replay', undefined, 'Replay')}
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
          <div
            data-testid="open-platform-contract-health-grid"
            className="grid min-w-0 items-start gap-4 lg:grid-cols-2"
          >
            <div
              className="min-w-0 overflow-hidden rounded-lg border border-gray-200 p-3 dark:border-gray-700"
              data-testid="open-platform-event-catalog"
            >
              <h4 className="text-xs font-semibold tracking-wide text-gray-500 uppercase">
                {t('openPlatform.eventCatalog', undefined, 'Event catalog')}
              </h4>
              <p className="mt-1 text-xs text-gray-400">
                {t(
                  'openPlatform.eventCatalogHint',
                  undefined,
                  'Only explicitly published partner events can be selected by installed applications.',
                )}
              </p>
              {eventCatalog.length === 0 ? (
                <div className="mt-3">
                  <Empty
                    label={t(
                      'openPlatform.noPublishedEvents',
                      undefined,
                      'No partner events published',
                    )}
                  />
                </div>
              ) : (
                <div className="mt-3 space-y-2">
                  {eventCatalog.map((event) => (
                    <div
                      key={event.type}
                      className="min-w-0 rounded-md bg-slate-50 p-2 dark:bg-slate-950"
                    >
                      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
                        <code className="min-w-0 text-xs break-all text-gray-800 dark:text-gray-100">
                          {event.type}
                        </code>
                        <span className="rounded-full bg-indigo-100 px-2 py-0.5 text-[11px] text-indigo-700">
                          {event.classification} · v{event.currentVersion}
                        </span>
                      </div>
                      <p className="mt-1 text-[11px] text-gray-400">
                        {event.subjectResourceCode} · compatible v
                        {event.supportedVersions.join(', v')}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div
              className="min-w-0 overflow-hidden rounded-lg border border-gray-200 p-3 dark:border-gray-700"
              data-testid="open-platform-webhook-health"
            >
              <h4 className="text-xs font-semibold tracking-wide text-gray-500 uppercase">
                {t('openPlatform.webhookHealth', undefined, 'Webhook compatibility & signing')}
              </h4>
              <p className="mt-1 text-xs text-gray-400">
                {t(
                  'openPlatform.webhookHealthHint',
                  undefined,
                  'Rotate signing secrets every 90 days and keep subscriptions on a supported schema.',
                )}
              </p>
              {webhookHealth.length === 0 ? (
                <div className="mt-3">
                  <Empty
                    label={t(
                      'openPlatform.noInstalledWebhooks',
                      undefined,
                      'No webhooks for this installation',
                    )}
                  />
                </div>
              ) : (
                <div className="mt-3 space-y-2">
                  {webhookHealth.map((item) => (
                    <WebhookHealthCard key={item.pid} item={item} />
                  ))}
                </div>
              )}
            </div>
          </div>
        </>
      )}
      {pendingReplay && (
        <div
          className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"
          role="alert"
        >
          <div className="flex gap-2">
            <ExclamationTriangleIcon className="h-5 w-5 shrink-0" />
            <div>
              <p className="font-medium">
                {t('openPlatform.replayConfirmTitle', undefined, 'Replay this delivery?')}
              </p>
              <p className="mt-1 text-xs">
                {t(
                  'openPlatform.replayConfirmBody',
                  undefined,
                  'The original signed payload will be delivered again and may repeat downstream side effects.',
                )}
              </p>
              <div className="mt-3 flex gap-2">
                <button
                  type="button"
                  onClick={() => setPendingReplay(null)}
                  className="rounded border border-amber-400 px-2 py-1"
                >
                  {t('common.cancel', undefined, 'Cancel')}
                </button>
                <button
                  type="button"
                  onClick={() => void replay()}
                  className="rounded bg-amber-700 px-2 py-1 text-white"
                >
                  {t('openPlatform.confirmReplay', undefined, 'Confirm replay')}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

function WebhookHealthCard({ item }: { item: WebhookHealth }) {
  const warning =
    !item.compatible || item.rotationStatus === 'overdue' || item.rotationStatus === 'missing';
  return (
    <div
      className={`min-w-0 rounded-md border p-2 text-xs ${
        warning
          ? 'border-amber-300 bg-amber-50 text-amber-950 dark:bg-amber-950/30 dark:text-amber-100'
          : 'border-transparent bg-slate-50 text-gray-700 dark:bg-slate-950 dark:text-gray-200'
      }`}
      data-rotation-status={item.rotationStatus}
      data-compatible={String(item.compatible)}
    >
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <span className="min-w-0 font-medium break-words">{item.name}</span>
        <span className="font-semibold">{item.compatible ? 'Compatible' : 'Action required'}</span>
      </div>
      <code className="mt-1 block text-[11px] break-all">{item.eventType}</code>
      <p className="mt-1">
        schema v{item.eventVersion}
        {item.catalogCurrentVersion ? ` · catalog v${item.catalogCurrentVersion}` : ''}
        {' · '}
        signing {item.rotationStatus}
      </p>
      {item.rotationDueAt && (
        <p className="mt-1 text-[11px] opacity-75">Rotate by {formatTime(item.rotationDueAt)}</p>
      )}
      {!item.compatible && <p className="mt-1 font-medium">Choose a supported event version.</p>}
      {item.rotationStatus === 'overdue' && (
        <p className="mt-1 font-medium">Rotate the signing secret now.</p>
      )}
      {item.rotationStatus === 'missing' && (
        <p className="mt-1 font-medium">Add a signing secret before enabling delivery.</p>
      )}
    </div>
  );
}

function Empty({ label }: { label: string }) {
  return (
    <div className="rounded-md border border-dashed border-gray-300 p-5 text-center text-xs text-gray-400 dark:border-gray-700">
      {label}
    </div>
  );
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}
