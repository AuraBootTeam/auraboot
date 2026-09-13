import { useCallback, useEffect, useRef, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { Button } from '~/ui/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '~/ui/ui/dialog';

type ResultPage = {
  records: { eventId: string; modelLabel: string | null; operation: string; recordedAt: string }[];
  page: number;
  pageSize: number;
  hasMore: boolean;
};

/** Reads committed business facts on demand, independently of task status. */
export function AnalyticsBusinessResults({ adoptionPid }: { adoptionPid: string }) {
  const { locale } = useI18n();
  const l = (zh: string, en: string) => (locale === 'zh-CN' ? zh : en);
  const [open, setOpen] = useState(false);
  const [result, setResult] = useState<ResultPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const generation = useRef(0);
  const load = useCallback(
    async (page = 1) => {
      const current = ++generation.current;
      setLoading(true);
      setFailed(false);
      setResult(null);
      try {
        const response = await fetchResult<ResultPage>(
          `/api/analytics/suggestions/${adoptionPid}/business-results`,
          {
            method: 'get',
            params: { page: String(page), pageSize: '10' },
          },
        );
        if (!ResultHelper.isSuccess(response) || !response.data)
          throw new Error('Business result read failed');
        if (current === generation.current) setResult(response.data);
      } catch {
        if (current === generation.current) setFailed(true);
      } finally {
        if (current === generation.current) setLoading(false);
      }
    },
    [adoptionPid],
  );
  useEffect(() => {
    if (open) void load();
    return () => {
      generation.current++;
    };
  }, [open, load]);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          {l('查看业务结果', 'View business results')}
        </Button>
      </DialogTrigger>
      <DialogContent
        className="max-h-[85vh] max-w-[calc(100vw-2rem)] overflow-y-auto sm:max-w-lg"
        onEscapeKeyDown={(event) => event.stopPropagation()}
      >
        <DialogHeader>
          <DialogTitle>{l('已提交的业务操作', 'Committed business operations')}</DialogTitle>
          <DialogDescription>
            {l(
              '仅展示当前有权读取的已提交操作，不代表业务收益或目标达成。',
              'Shows committed operations readable with your current access, not business benefit or goal achievement.',
            )}
          </DialogDescription>
        </DialogHeader>
        {loading && (
          <p role="status" className="text-muted-foreground text-sm">
            {l('正在读取业务结果…', 'Loading business results…')}
          </p>
        )}
        {failed && (
          <p role="alert" className="text-destructive text-sm">
            {l(
              '无法读取业务结果，请确认当前权限或稍后重试。',
              'Unable to read business results. Check current access or try again later.',
            )}
          </p>
        )}
        {result && result.records.length === 0 && (
          <p className="bg-muted rounded-md p-3 text-sm">
            {l(
              '尚无已记录的提交结果。任务成功不等于业务操作已提交。',
              'No committed results recorded. Task success does not establish a committed business operation.',
            )}
          </p>
        )}
        {result && result.records.length > 0 && (
          <ul className="space-y-2" aria-label={l('业务操作记录', 'Business operation records')}>
            {result.records.map((record) => (
              <li key={record.eventId} className="rounded-md border p-3 text-sm">
                <p className="font-medium break-words">
                  {record.modelLabel || l('业务记录', 'Business record')} ·{' '}
                  {record.operation === 'create'
                    ? l('新增已提交', 'Creation committed')
                    : record.operation === 'update'
                      ? l('更新已提交', 'Update committed')
                      : l('删除已提交', 'Deletion committed')}
                </p>
                <time className="text-muted-foreground text-xs" dateTime={record.recordedAt}>
                  {new Date(record.recordedAt).toLocaleString(locale)}
                </time>
              </li>
            ))}
          </ul>
        )}
        <div className="flex flex-wrap justify-end gap-2">
          {result && result.page > 1 && (
            <Button
              size="sm"
              variant="outline"
              disabled={loading}
              onClick={() => void load(result.page - 1)}
            >
              {l('上一页', 'Previous')}
            </Button>
          )}
          {result?.hasMore && (
            <Button
              size="sm"
              variant="outline"
              disabled={loading}
              onClick={() => void load(result.page + 1)}
            >
              {l('下一页', 'Next')}
            </Button>
          )}
          <Button size="sm" variant="outline" disabled={loading} onClick={() => void load()}>
            {l('重新读取', 'Reload results')}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
