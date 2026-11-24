import { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { HttpMessageDetails, type HttpMessages } from '~/ui/smart/diagnostics/HttpMessageDetails';
import { openPlatformApiFetch, OpenPlatformApiError } from './open-platform-api';

interface Detail { httpDetails?: HttpMessages | null }
interface Result { key: string; detail?: Detail; error?: 'forbidden' | 'failed' }

/** Adds message disclosure to the existing installation audit row; never creates another log entry. */
export function CallAuditMessageDetails({ installationPid, auditPid }: { installationPid: string; auditPid: string }) {
  const { locale, t } = useI18n();
  const label = (zh: string, en: string) => getLocalizedText({ 'zh-CN': zh, en }, locale, t);
  const [open, setOpen] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<Result | null>(null);
  const key = `${installationPid}/${auditPid}`;

  useEffect(() => {
    setResult(null);
    if (!open) return;
    const controller = new AbortController();
    void openPlatformApiFetch<Detail>(`/api/open-platform/installations/${installationPid}/audits/${auditPid}`, { signal: controller.signal })
      .then(detail => { if (!controller.signal.aborted) setResult({ key, detail }); })
      .catch(error => {
        if (!controller.signal.aborted) setResult({ key, error: error instanceof OpenPlatformApiError && error.status === 403 ? 'forbidden' : 'failed' });
      });
    return () => controller.abort();
  }, [open, key, installationPid, auditPid, attempt]);

  const current = result?.key === key ? result : null;
  const historical = { state: 'not_captured' as const };
  return <div className="mt-2 border-t border-gray-200 pt-2 dark:border-gray-700">
    <button type="button" onClick={() => setOpen(value => !value)} aria-expanded={open}
      className="rounded px-1 py-1 text-xs font-medium text-blue-600 focus-visible:outline-2 focus-visible:outline-blue-500"
      data-testid="call-audit-messages-toggle">
      {open ? label('收起请求与响应', 'Hide request and response') : label('查看请求与响应', 'View request and response')}
    </button>
    {open && <div className="mt-2" data-testid="call-audit-messages">
      {!current ? <p role="status" className="text-xs text-gray-500">{label('正在读取报文…', 'Loading messages…')}</p>
        : current.error ? <div role="alert" className="space-y-2 text-xs text-red-600">
          <p>{current.error === 'forbidden' ? label('没有查看报文的权限', 'You do not have permission to view these messages') : label('无法读取日志详情', 'Unable to load log details')}</p>
          {current.error !== 'forbidden' && <button type="button" className="underline" onClick={() => setAttempt(value => value + 1)}>{label('重试', 'Retry')}</button>}
        </div>
          : <HttpMessageDetails {...(current.detail?.httpDetails ?? { request: historical, response: historical })} />}
    </div>}
  </div>;
}
