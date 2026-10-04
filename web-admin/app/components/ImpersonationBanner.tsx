import { ClockIcon, ArrowRightOnRectangleIcon } from '@heroicons/react/24/outline';
import { useFetcher } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';

export function ImpersonationBanner() {
  const { impersonation } = useAuth();
  const { locale } = useI18n();
  const fetcher = useFetcher<{ ok?: boolean; error?: string }>();
  if (!impersonation) return null;

  const zh = locale === 'zh-CN';
  const expiresAt = new Intl.DateTimeFormat(locale, {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(impersonation.expiresAt));

  return (
    <div
      className="print-hide border-b border-amber-300 bg-amber-50 px-4 py-2.5 text-amber-950 dark:border-amber-800 dark:bg-amber-950/60 dark:text-amber-100"
      data-testid="impersonation-banner"
      role="status"
    >
      <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-semibold">
            {zh ? '正在以客户身份操作：' : 'Acting as customer: '}
            <span data-testid="impersonation-target">{impersonation.targetDisplayName}</span>
          </p>
          <p className="mt-0.5 flex items-center gap-1.5 text-xs text-amber-800 dark:text-amber-200">
            <ClockIcon className="h-3.5 w-3.5" aria-hidden="true" />
            {zh
              ? `会话将在 ${expiresAt} 到期，本次代登录会话会被记录`
              : `Session expires at ${expiresAt}; this delegated session is recorded`}
          </p>
          <p className="mt-0.5 text-xs text-amber-800 dark:text-amber-200">
            {zh ? '实际操作者：' : 'Actual operator: '}
            {impersonation.operatorDisplayName}
          </p>
          {fetcher.data?.error && (
            <p className="mt-1 text-xs font-medium text-red-700 dark:text-red-300" role="alert">
              {fetcher.data.error}
            </p>
          )}
        </div>
        <fetcher.Form method="post" action="/_action/end-impersonation">
          <button
            type="submit"
            disabled={fetcher.state !== 'idle'}
            className="inline-flex items-center gap-1.5 rounded-lg border border-amber-400 bg-white px-3 py-1.5 text-sm font-medium text-amber-900 shadow-sm hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60 dark:border-amber-700 dark:bg-amber-900/60 dark:text-amber-100 dark:hover:bg-amber-900"
            data-testid="end-impersonation"
          >
            <ArrowRightOnRectangleIcon className="h-4 w-4" aria-hidden="true" />
            {fetcher.state !== 'idle'
              ? zh
                ? '正在退出…'
                : 'Ending…'
              : zh
                ? '退出客户身份'
                : 'Return to admin'}
          </button>
        </fetcher.Form>
      </div>
    </div>
  );
}
