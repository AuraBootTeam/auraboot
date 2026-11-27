import { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { getApiService } from '~/shared/services/ApiService';
import { HttpMessageDetails, storedHttpBody } from '~/ui/smart/diagnostics/HttpMessageDetails';

interface Delivery {
  pid: string;
  eventId?: string;
  requestId?: string;
  requestBody?: string | null;
  responseBody?: string | null;
  responseStatus?: number;
  deliveryStatus: string;
  retryCount: number;
  errorMessage?: string;
  createdAt: string;
}

/** Read-only history in the existing Webhook DSL detail, using the existing authorized API. */
export function WebhookDeliveryHistory({ runtime }: {
  runtime?: { getContext: () => { $page?: { recordPid?: string } } };
}) {
  const recordPid = runtime?.getContext().$page?.recordPid;
  const { locale, t } = useI18n();
  const label = (zh: string, en: string) => getLocalizedText({ 'zh-CN': zh, en }, locale, t);
  const [state, setState] = useState<{ pid?: string; rows?: Delivery[]; failed?: boolean }>({});
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    setState({});
    if (!recordPid) return;
    const controller = new AbortController();
    void getApiService().get<Delivery[]>(`/webhooks/${encodeURIComponent(recordPid)}/deliveries`, undefined, controller.signal)
      .then(result => {
        if (controller.signal.aborted) return;
        setState(result.success ? { pid: recordPid, rows: result.data } : { pid: recordPid, failed: true });
      }).catch(() => { if (!controller.signal.aborted) setState({ pid: recordPid, failed: true }); });
    return () => controller.abort();
  }, [recordPid, attempt]);
  const current = state.pid === recordPid ? state : null;
  const status = (value: string) => { const labels: Record<string, string> = {
    success: label('已送达', 'Delivered'), failed: label('失败', 'Failed'),
    pending: label('待投递', 'Pending'), processing: label('投递中', 'Processing'),
    dead_letter: label('死信', 'Dead letter'),
  }; return labels[value] ?? label('未知状态', 'Unknown status'); };
  return <section className="min-w-0 space-y-3" data-testid="webhook-delivery-history">
    <h3 className="text-sm font-semibold">{label('投递日志', 'Delivery logs')}</h3>
    {!recordPid ? <p role="alert">{label('缺少订阅上下文', 'Subscription context is missing')}</p>
      : !current ? <p role="status" className="text-sm text-gray-500">{label('正在读取投递日志…', 'Loading delivery logs…')}</p>
        : current.failed ? <div role="alert" className="space-y-2 text-sm text-red-600">
          <p>{label('无法读取投递日志，请确认当前权限或稍后重试', 'Unable to read delivery logs. Check your access or retry later.')}</p>
          <button type="button" className="underline" onClick={() => setAttempt(value => value + 1)}>{label('重试', 'Retry')}</button>
        </div>
          : !current.rows?.length ? <p className="text-sm text-gray-500">{label('暂无投递记录', 'No delivery records')}</p>
            : current.rows.map(log => <details key={`${recordPid}/${log.pid}`} className="min-w-0 rounded-lg border border-gray-200 dark:border-gray-700" data-testid={`webhook-delivery-log-${log.pid}`}>
              <summary className="cursor-pointer space-x-3 p-3 text-xs">
                <span className="font-medium">{status(log.deliveryStatus)}</span>
                {log.responseStatus != null && <span>HTTP {log.responseStatus}</span>}
                <span>{label('重试', 'Retries')}: {log.retryCount}</span>
                <span className="text-gray-500">{new Date(log.createdAt).toLocaleString(locale)}</span>
              </summary>
              <div className="space-y-3 border-t border-gray-100 p-3 dark:border-gray-700">
                {log.eventId && <p className="break-all text-xs text-gray-500">{label('事件标识', 'Event ID')}: <code>{log.eventId}</code></p>}
                {log.requestId && <p className="break-all text-xs text-gray-500">{label('请求标识', 'Request ID')}: <code>{log.requestId}</code></p>}
                {log.errorMessage && <p className="break-all text-xs text-red-600">{log.errorMessage}</p>}
                <HttpMessageDetails request={storedHttpBody(log.requestBody)} response={storedHttpBody(log.responseBody)} />
              </div>
            </details>)}
  </section>;
}
