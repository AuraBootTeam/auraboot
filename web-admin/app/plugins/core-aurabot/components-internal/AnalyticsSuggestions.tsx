import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { usePermission } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { Button } from '~/ui/ui/button';
import { Input } from '~/ui/ui/input';
import { Textarea } from '~/ui/ui/textarea';
import { Label } from '~/ui/ui/label';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '~/ui/ui/dialog';
import type { ChartDataSource } from '~/framework/smart/types/chart';
import { toast } from 'sonner';
import { useAuraBot } from '../components-shell/AuraBotProvider';

type Suggestion = {
  pid: string;
  title: string;
  content: string;
  executionGoal?: string | null;
  execution?: { state: string; attempts: number } | null;
  version: number;
  groupKey: string;
  origin: string;
  adoptionPid: string | null;
};
type SuggestionPage = { records: Suggestion[]; total: number; page: number; pageSize: number };
type CommandResult = { data: { record: { pid: string } } };

/** Inline actions backed by immutable DSL commands; the query never becomes a form input. */
export function AnalyticsSuggestions({
  analysisId,
  query,
}: {
  analysisId: string;
  query: ChartDataSource;
}) {
  const { state: conversationState, sendMessage } = useAuraBot();
  const executionPermission = usePermission('analytics.suggestion.execute');
  const [executionConfirmation, setExecutionConfirmation] = useState<Suggestion | null>(null);
  const canRead = usePermission('analytics.suggestion.read');
  const canExecute = usePermission('meta.command.execute');
  const proposalPermission = usePermission('analytics.suggestion.propose');
  const adoptionPermission = usePermission('analytics.suggestion.adopt');
  const canPropose = canExecute && proposalPermission;
  const canAdopt = canExecute && adoptionPermission;
  const { locale } = useI18n();
  const l = (zh: string, en: string) => (locale === 'zh-CN' ? zh : en);
  const inputId = useId();
  const [rows, setRows] = useState<Suggestion[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Suggestion | null>(null);
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [confirmation, setConfirmation] = useState<Suggestion | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState(false);
  const [invalid, setInvalid] = useState(false);
  const requestIdentity = useRef<{ signature: string; id: string } | null>(null);
  const generation = useRef(0);
  const wasRunning = useRef(conversationState.isLoading);
  const sectionRef = useRef<HTMLElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const rememberOpener = () => {
    openerRef.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
  };
  const restoreFocus = (event: Event) => {
    event.preventDefault();
    (openerRef.current?.isConnected ? openerRef.current : sectionRef.current)?.focus();
  };

  const load = useCallback(
    async (nextPage = 1) => {
      const current = ++generation.current;
      if (!canRead) {
        setRows([]);
        return;
      }
      setLoading(true);
      setLoadError(false);
      try {
        const result = await fetchResult<SuggestionPage>('/api/analytics/suggestions', {
          method: 'get',
          params: { analysisId, page: String(nextPage), pageSize: '5' },
        });
        if (!ResultHelper.isSuccess(result) || !result.data)
          throw new Error('Suggestion read failed');
        if (current !== generation.current) return;
        const data = result.data;
        setRows((previous) =>
          nextPage === 1
            ? data.records
            : [
                ...previous,
                ...data.records.filter((row) => !previous.some((old) => old.pid === row.pid)),
              ],
        );
        setTotal(data.total);
        setPage(data.page);
      } catch {
        if (current === generation.current) {
          setRows([]);
          setLoadError(true);
        }
      } finally {
        if (current === generation.current) setLoading(false);
      }
    },
    [analysisId, canRead],
  );
  useEffect(() => {
    void load();
    return () => {
      generation.current++;
    };
  }, [load]);

  useEffect(() => {
    if (wasRunning.current && !conversationState.isLoading) void load();
    wasRunning.current = conversationState.isLoading;
  }, [conversationState.isLoading, load]);
  const executionLabel = (state: string) => {
    const labels: Record<string, string> = {
      not_started: l('任务已创建，尚未开始', 'Task created, not started'),
      running: l('已开始，等待结果', 'Started, awaiting outcome'),
      pending: l('等待审批', 'Awaiting approval'),
      queued: l('等待调度', 'Queued'),
      success: l('执行成功', 'Execution succeeded'),
      failed: l('执行失败', 'Execution failed'),
      cancelled: l('执行已取消', 'Execution cancelled'),
      unavailable: l('任务已移除', 'Task unavailable'),
    };
    return labels[state] || l('状态待确认', 'Status unknown');
  };

  const execute = async (command: string, payload: Record<string, unknown>) => {
    const signature = JSON.stringify({ command, payload });
    if (requestIdentity.current?.signature !== signature)
      requestIdentity.current = { signature, id: crypto.randomUUID() };
    const result = await fetchResult<CommandResult>(
      `/api/meta/commands/execute/core_dashboard:${command}`,
      {
        method: 'post',
        params: { payload: { ...payload, requestId: requestIdentity.current.id } },
      },
    );
    if (!ResultHelper.isSuccess(result) || !result.data?.data?.record?.pid)
      throw new Error('Suggestion command failed');
  };
  const openForm = (previous: Suggestion | null) => {
    rememberOpener();
    setEditing(previous);
    setTitle(previous?.title || '');
    setContent(previous?.content || '');
    setActionError(false);
    setInvalid(false);
    requestIdentity.current = null;
    setFormOpen(true);
  };
  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!title.trim() || !content.trim()) {
      setInvalid(true);
      return;
    }
    if (busy) return;
    setBusy(true);
    setActionError(false);
    try {
      await execute('propose_suggestion', {
        analysisId,
        query,
        title: title.trim(),
        content: content.trim(),
        ...(editing ? { previousPid: editing.pid } : {}),
      });
      setFormOpen(false);
      toast.success(l('建议版本已保存', 'Suggestion version saved'));
      await load();
    } catch {
      setActionError(true);
    } finally {
      setBusy(false);
    }
  };
  const adopt = async () => {
    if (!confirmation || busy) return;
    setBusy(true);
    setActionError(false);
    try {
      await execute('adopt_suggestion', { versionPid: confirmation.pid });
      setConfirmation(null);
      toast.success(l('采纳决定已保存', 'Adoption decision saved'));
      await load();
    } catch {
      setActionError(true);
    } finally {
      setBusy(false);
    }
  };
  if (!canRead) return null;
  const latest = new Set(
    rows
      .filter((row, index) => rows.findIndex((other) => other.groupKey === row.groupKey) === index)
      .map((row) => row.pid),
  );
  const errorText = l(
    '操作未完成。请刷新建议并检查权限后重试；修订时请使用最新版本。',
    'The action did not complete. Refresh suggestions and check permissions before retrying; revise the latest version.',
  );

  return (
    <section
      ref={sectionRef}
      tabIndex={-1}
      data-testid="analytics-suggestions"
      aria-label={l('分析建议', 'Analysis suggestions')}
      className="space-y-3 border-t border-gray-100 p-3 dark:border-gray-700"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-medium">{l('分析建议', 'Analysis suggestions')}</h3>
        <Button size="sm" variant="ghost" disabled={loading} onClick={() => void load()}>
          {l('刷新建议', 'Refresh suggestions')}
        </Button>
        {canPropose && (
          <Button size="sm" variant="outline" onClick={() => openForm(null)}>
            {l('记录建议', 'Record suggestion')}
          </Button>
        )}
      </div>
      <p className="text-xs text-gray-500">
        {l(
          '建议按版本保存；采纳仅记录你的决定，执行需单独发起。',
          'Suggestions are versioned. Adoption records your decision; execution is a separate action.',
        )}
      </p>
      {loading && (
        <p role="status" className="text-sm text-gray-500">
          {l('正在加载建议…', 'Loading suggestions…')}
        </p>
      )}
      {loadError && (
        <div role="alert" className="space-y-2 text-sm text-red-600">
          <p>
            {l(
              '无法加载建议，请检查权限或重试。',
              'Could not load suggestions. Check access or retry.',
            )}
          </p>
          <Button size="sm" variant="outline" onClick={() => void load()}>
            {l('重新加载', 'Reload')}
          </Button>
        </div>
      )}
      {!loading && !loadError && rows.length === 0 && (
        <p className="rounded-md bg-gray-50 p-3 text-sm text-gray-500 dark:bg-gray-900">
          {l(
            '还没有已记录的建议。可根据本次分析记录下一步建议，再决定是否采纳。',
            'No suggestions recorded. Record a next step from this analysis, then decide whether to adopt it.',
          )}
        </p>
      )}
      {rows.map((row) => (
        <article
          key={row.pid}
          data-testid="analytics-suggestion"
          className="min-w-0 space-y-2 rounded-lg border border-gray-200 p-3 dark:border-gray-700"
        >
          <div className="flex flex-wrap items-center gap-2">
            <h4 className="min-w-0 text-sm font-medium break-words">{row.title}</h4>
            <span className="text-xs text-gray-500">
              {l('版本', 'Version')} {row.version}
            </span>
          </div>
          <p className="max-h-48 overflow-y-auto text-sm break-words whitespace-pre-wrap text-gray-600 dark:text-gray-300">
            {row.content}
          </p>
          {row.executionGoal && (
            <p className="max-h-48 overflow-y-auto text-sm break-words whitespace-pre-wrap">
              {l('执行目标', 'Execution goal')}: {row.executionGoal}
            </p>
          )}
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-xs text-gray-500">
              {row.origin === 'agent_generated'
                ? l('AI 建议', 'AI suggestion')
                : l('人工记录', 'Recorded by user')}
            </span>
            <div className="flex flex-wrap items-center gap-2">
              {row.adoptionPid ? (
                <span className="text-xs font-medium text-green-700 dark:text-green-400">
                  {l('已采纳', 'Adopted')}
                </span>
              ) : (
                canAdopt && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => {
                      setActionError(false);
                      requestIdentity.current = null;
                      rememberOpener();
                      setConfirmation(row);
                    }}
                  >
                    {l('采纳此版本', 'Adopt this version')}
                  </Button>
                )
              )}
              {row.execution && (
                <span
                  data-testid="analytics-execution-status"
                  className="text-xs text-gray-600 dark:text-gray-300"
                >
                  {executionLabel(row.execution.state)} · {l('运行次数', 'Attempts')}:{' '}
                  {row.execution.attempts}
                </span>
              )}
              {row.adoptionPid &&
                row.executionGoal &&
                executionPermission &&
                (!row.execution || row.execution.state === 'not_started') && (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={conversationState.isLoading}
                    onClick={() => {
                      rememberOpener();
                      setExecutionConfirmation(row);
                    }}
                  >
                    {l('发起执行', 'Start execution')}
                  </Button>
                )}
              {canPropose && latest.has(row.pid) && (
                <Button size="sm" variant="ghost" onClick={() => openForm(row)}>
                  {l('修订建议', 'Revise suggestion')}
                </Button>
              )}
            </div>
          </div>
        </article>
      ))}
      {!loadError && rows.length < total && (
        <Button variant="ghost" size="sm" disabled={loading} onClick={() => void load(page + 1)}>
          {l('加载更多版本', 'Load more versions')}
        </Button>
      )}
      <Dialog
        open={formOpen}
        onOpenChange={(open) => {
          if (!busy) setFormOpen(open);
        }}
      >
        <DialogContent
          onCloseAutoFocus={restoreFocus}
          className="max-h-[85vh] max-w-[calc(100vw-2rem)] overflow-y-auto sm:max-w-lg"
        >
          <DialogHeader>
            <DialogTitle>
              {editing ? l('修订建议', 'Revise suggestion') : l('记录建议', 'Record suggestion')}
            </DialogTitle>
            <DialogDescription>
              {l(
                '保存为独立版本，已有版本和采纳决定会保留。',
                'Save a distinct version. Earlier versions and adoption decisions remain unchanged.',
              )}
            </DialogDescription>
          </DialogHeader>
          <form noValidate onSubmit={save} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor={`${inputId}-title`}>{l('建议标题', 'Suggestion title')}</Label>
              <Input
                id={`${inputId}-title`}
                required
                maxLength={200}
                value={title}
                disabled={busy}
                aria-invalid={invalid && !title.trim()}
                onChange={(event) => setTitle(event.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor={`${inputId}-content`}>{l('建议内容', 'Suggestion content')}</Label>
              <Textarea
                id={`${inputId}-content`}
                required
                maxLength={4000}
                rows={5}
                value={content}
                disabled={busy}
                aria-invalid={invalid && !content.trim()}
                onChange={(event) => setContent(event.target.value)}
              />
            </div>
            {invalid && (!title.trim() || !content.trim()) && (
              <p role="alert" className="text-sm text-red-600">
                {l('请填写建议标题和内容。', 'Enter a title and suggestion content.')}
              </p>
            )}
            {actionError && (
              <p role="alert" className="text-sm text-red-600">
                {errorText}
              </p>
            )}
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={busy}
                onClick={() => setFormOpen(false)}
              >
                {l('取消', 'Cancel')}
              </Button>
              <Button type="submit" disabled={busy}>
                {busy ? l('保存中…', 'Saving…') : l('保存建议版本', 'Save suggestion version')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
      <Dialog
        open={confirmation !== null}
        onOpenChange={(open) => {
          if (!busy && !open) setConfirmation(null);
        }}
      >
        <DialogContent
          onCloseAutoFocus={restoreFocus}
          className="max-h-[85vh] max-w-[calc(100vw-2rem)] overflow-y-auto sm:max-w-lg"
        >
          <DialogHeader>
            <DialogTitle>{l('确认采纳此版本', 'Confirm adoption')}</DialogTitle>
            <DialogDescription>
              {l(
                '这会保存你的采纳决定，执行需另行发起。',
                'This saves your adoption decision. Execution is a separate action.',
              )}
            </DialogDescription>
          </DialogHeader>
          <p className="text-sm font-medium break-words">
            {confirmation?.title} · {l('版本', 'Version')} {confirmation?.version}
          </p>
          <p className="max-h-48 overflow-y-auto text-sm break-words whitespace-pre-wrap">
            {confirmation?.content}
          </p>
          {confirmation?.executionGoal && (
            <p className="max-h-48 overflow-y-auto text-sm break-words whitespace-pre-wrap">
              {l('执行目标', 'Execution goal')}: {confirmation.executionGoal}
            </p>
          )}
          {actionError && (
            <p role="alert" className="text-sm text-red-600">
              {errorText}
            </p>
          )}
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setConfirmation(null)}>
              {l('取消', 'Cancel')}
            </Button>
            <Button disabled={busy} onClick={() => void adopt()}>
              {busy ? l('保存中…', 'Saving…') : l('确认采纳', 'Confirm adoption')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!executionConfirmation}
        onOpenChange={(open) => {
          if (!open) setExecutionConfirmation(null);
        }}
      >
        <DialogContent onCloseAutoFocus={restoreFocus}>
          <DialogHeader>
            <DialogTitle>{l('执行已采纳建议', 'Execute adopted suggestion')}</DialogTitle>
            <DialogDescription>
              {l(
                '确认后将按此版本的目标发起任务。进展和错误会显示在对话中；执行结果不代表业务收益。',
                'Start a task using this version’s goal. Progress and errors appear in the conversation; execution results do not establish business benefit.',
              )}
            </DialogDescription>
          </DialogHeader>
          <p className="text-sm font-medium break-words">{executionConfirmation?.title}</p>
          <p className="max-h-48 overflow-y-auto text-sm break-words whitespace-pre-wrap">
            {executionConfirmation?.executionGoal}
          </p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setExecutionConfirmation(null)}>
              {l('取消', 'Cancel')}
            </Button>
            <Button
              disabled={conversationState.isLoading || !executionPermission}
              onClick={() => {
                if (
                  !executionConfirmation?.adoptionPid ||
                  conversationState.isLoading ||
                  !executionPermission
                )
                  return;
                const selected = executionConfirmation;
                setExecutionConfirmation(null);
                sendMessage(
                  l('执行已采纳建议：', 'Execute adopted suggestion: ') + selected.title,
                  undefined,
                  {
                    adoptionPid: selected.adoptionPid!,
                    requestId: crypto.randomUUID(),
                  },
                );
              }}
            >
              {l('确认执行', 'Confirm execution')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
