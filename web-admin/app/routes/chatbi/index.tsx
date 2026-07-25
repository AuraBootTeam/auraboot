/**
 * /semantic/ask — ChatBI v2 conversational analytics console.
 *
 * Wires the previously-headless {@code /api/chatbi/v2/**} surface into a UI:
 * conversation lifecycle (create / list / close) + ask, rendering all three
 * answer states — SUCCESS (rows + SQL + confidence), DISAMBIGUATION (pick a
 * candidate), FAILED (error). NL translation needs a configured LLM provider;
 * the banner says so plainly rather than silently returning nothing.
 */

import { useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import { useI18n } from '~/contexts/I18nContext';
import {
  listConversations,
  createConversation,
  closeConversation,
  ask,
  recordDisambiguation,
  type ChatBiConversation,
  type ChatBiAnswer,
} from '~/plugins/core-semantic/api/chatbiApi';

interface Turn {
  question: string;
  answer?: ChatBiAnswer;
  pending?: boolean;
  error?: string;
}

export default function ChatBiPage() {
  const { t } = useI18n();
  const [conversations, setConversations] = useState<ChatBiConversation[]>([]);
  const [activePid, setActivePid] = useState<string | null>(null);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [convError, setConvError] = useState<string | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);

  async function reloadConversations() {
    setConvError(null);
    try {
      const list = await listConversations(20);
      setConversations(list);
    } catch (e) {
      setConvError(e instanceof Error ? e.message : String(e));
    }
  }

  useEffect(() => {
    void reloadConversations();
  }, []);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
  }, [turns]);

  async function newConversation() {
    try {
      const pid = await createConversation();
      setActivePid(pid);
      setTurns([]);
      await reloadConversations();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e));
    }
  }

  async function selectConversation(pid: string) {
    setActivePid(pid);
    setTurns([]); // history replay is out of scope; start a fresh thread view
  }

  async function closeConv(pid: string) {
    try {
      await closeConversation(pid);
      if (activePid === pid) {
        setActivePid(null);
        setTurns([]);
      }
      await reloadConversations();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e));
    }
  }

  async function send() {
    const q = input.trim();
    if (!q) return;
    let pid = activePid;
    if (!pid) {
      try {
        pid = await createConversation();
        setActivePid(pid);
        await reloadConversations();
      } catch (e) {
        toast.error(e instanceof Error ? e.message : String(e));
        return;
      }
    }
    setInput('');
    setBusy(true);
    const idx = turns.length;
    setTurns((prev) => [...prev, { question: q, pending: true }]);
    try {
      const answer = await ask(pid, q);
      setTurns((prev) =>
        prev.map((tn, i) => (i === idx ? { question: q, answer } : tn)),
      );
    } catch (e) {
      setTurns((prev) =>
        prev.map((tn, i) =>
          i === idx
            ? { question: q, error: e instanceof Error ? e.message : String(e) }
            : tn,
        ),
      );
    } finally {
      setBusy(false);
    }
  }

  async function pickCandidate(
    turnIdx: number,
    logPid: string,
    code: string,
  ) {
    if (!activePid) return;
    try {
      await recordDisambiguation(activePid, logPid, code);
      toast.success(t('chatbi.disambiguation_recorded', undefined, '已记录选择'));
      // Re-ask with the disambiguated term appended for context.
      const original = turns[turnIdx]?.question ?? '';
      setInput(`${original} (${code})`);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div
      data-testid="chatbi-page"
      className="flex h-full flex-col overflow-hidden"
    >
      <header className="flex flex-shrink-0 items-center justify-between border-b border-gray-200 bg-white px-4 py-3 dark:border-gray-700 dark:bg-gray-900">
        <h1 className="text-sm font-semibold text-gray-800 dark:text-gray-100">
          {t('chatbi.title', undefined, '对话式分析')}
        </h1>
      </header>

      {/* Honest capability banner: NL needs an LLM provider. */}
      <div
        data-testid="chatbi-llm-banner"
        className="flex-shrink-0 border-b border-amber-200 bg-amber-50 px-4 py-1.5 text-[11px] text-amber-700 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-300"
      >
        {t(
          'chatbi.llm_hint',
          undefined,
          '自然语言翻译需在后端配置 LLM 提供方（aura.chatbi.v2.llm-provider = anthropic | openai）。未配置时问答会返回失败；治理指标可在「语义模型」控制台直接查询。',
        )}
      </div>

      <div className="flex min-h-0 flex-1">
        {/* Conversations sidebar */}
        <aside className="flex w-56 flex-shrink-0 flex-col border-r border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-950">
          <div className="flex items-center justify-between border-b border-gray-200 px-3 py-2 dark:border-gray-700">
            <span className="text-xs font-medium text-gray-500">
              {t('chatbi.conversations', undefined, '会话')}
            </span>
            <button
              type="button"
              data-testid="chatbi-new-conversation"
              onClick={() => void newConversation()}
              className="rounded bg-blue-600 px-2 py-0.5 text-xs font-medium text-white hover:bg-blue-700"
            >
              {t('chatbi.new', undefined, '新建')}
            </button>
          </div>
          <div className="min-h-0 flex-1 overflow-auto">
            {convError && (
              <div
                data-testid="chatbi-conv-error"
                className="px-3 py-2 text-xs text-red-500"
              >
                {convError}
              </div>
            )}
            {conversations.length === 0 && !convError && (
              <div className="px-3 py-3 text-xs text-gray-400">
                {t('chatbi.no_conversations', undefined, '暂无会话，点「新建」开始')}
              </div>
            )}
            {conversations.map((c) => (
              <div
                key={c.pid}
                data-testid={`chatbi-conversation-${c.pid}`}
                className={`group flex items-center justify-between border-b border-gray-100 px-3 py-2 text-sm dark:border-gray-800 ${
                  c.pid === activePid
                    ? 'bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300'
                    : 'text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-900'
                }`}
              >
                <button
                  type="button"
                  onClick={() => void selectConversation(c.pid)}
                  className="min-w-0 flex-1 truncate text-left"
                >
                  {c.title || c.pid.slice(0, 10)}
                </button>
                <button
                  type="button"
                  data-testid={`chatbi-close-${c.pid}`}
                  onClick={() => void closeConv(c.pid)}
                  className="ml-1 hidden text-xs text-gray-400 hover:text-red-500 group-hover:block"
                  title={t('chatbi.close', undefined, '关闭')}
                >
                  ×
                </button>
              </div>
            ))}
          </div>
        </aside>

        {/* Thread */}
        <section className="flex min-w-0 flex-1 flex-col">
          <div
            ref={threadRef}
            data-testid="chatbi-thread"
            className="min-h-0 flex-1 space-y-3 overflow-auto p-4"
          >
            {turns.length === 0 && (
              <div className="flex h-full items-center justify-center text-sm text-gray-400">
                {t('chatbi.empty_thread', undefined, '问一个关于你数据的问题，例如「按状态统计角色数」')}
              </div>
            )}
            {turns.map((tn, i) => (
              <TurnView key={i} turn={tn} idx={i} onPick={pickCandidate} t={t} />
            ))}
          </div>

          {/* Composer */}
          <div className="flex-shrink-0 border-t border-gray-200 p-3 dark:border-gray-700">
            <div className="flex items-end gap-2">
              <textarea
                data-testid="chatbi-input"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    void send();
                  }
                }}
                rows={2}
                placeholder={t('chatbi.input_placeholder', undefined, '输入问题，回车发送…')}
                className="min-h-0 flex-1 resize-none rounded border border-gray-200 p-2 text-sm focus:border-blue-400 focus:outline-none dark:border-gray-700 dark:bg-gray-900"
              />
              <button
                type="button"
                data-testid="chatbi-send"
                onClick={() => void send()}
                disabled={busy || !input.trim()}
                className="rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {busy
                  ? t('chatbi.sending', undefined, '发送中…')
                  : t('chatbi.send', undefined, '发送')}
              </button>
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}

function TurnView({
  turn,
  idx,
  onPick,
  t,
}: {
  turn: Turn;
  idx: number;
  onPick: (turnIdx: number, logPid: string, code: string) => void;
  t: ReturnType<typeof useI18n>['t'];
}) {
  const a = turn.answer;
  const cols = a?.rows?.length ? Object.keys(a.rows[0]) : [];
  return (
    <div data-testid={`chatbi-turn-${idx}`} className="space-y-2">
      {/* User question */}
      <div className="flex justify-end">
        <div className="max-w-[80%] rounded-lg bg-blue-600 px-3 py-2 text-sm text-white">
          {turn.question}
        </div>
      </div>

      {/* Assistant */}
      {turn.pending && (
        <div data-testid={`chatbi-pending-${idx}`} className="text-xs text-gray-400">
          {t('chatbi.thinking', undefined, '分析中…')}
        </div>
      )}
      {turn.error && (
        <div
          data-testid={`chatbi-turn-error-${idx}`}
          className="max-w-[80%] rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-600 dark:border-red-900 dark:bg-red-950"
        >
          {turn.error}
        </div>
      )}
      {a && a.status === 'FAILED' && (
        <div
          data-testid={`chatbi-failed-${idx}`}
          className="max-w-[80%] rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-600 dark:border-red-900 dark:bg-red-950"
        >
          {a.errorMessage || t('chatbi.failed', undefined, '无法回答该问题')}
        </div>
      )}
      {a && a.status === 'DISAMBIGUATION' && a.disambiguation && (
        <div
          data-testid={`chatbi-disambiguation-${idx}`}
          className="max-w-[80%] rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs dark:border-amber-900 dark:bg-amber-950"
        >
          <div className="mb-1 text-amber-700 dark:text-amber-300">
            {t('chatbi.which_did_you_mean', undefined, '你指的是哪个')}「{a.disambiguation.ambiguousTerm}」?
          </div>
          <div className="flex flex-wrap gap-1">
            {a.disambiguation.candidates.map((c) => (
              <button
                key={c.code}
                type="button"
                data-testid={`chatbi-candidate-${idx}-${c.code}`}
                onClick={() =>
                  onPick(idx, a.disambiguation!.disambiguationLogPid || '', c.code)
                }
                className="rounded border border-amber-300 bg-white px-2 py-0.5 text-amber-700 hover:bg-amber-100 dark:bg-gray-900 dark:text-amber-300"
              >
                {c.label} <span className="text-amber-400">({c.type})</span>
              </button>
            ))}
          </div>
        </div>
      )}
      {a && a.status === 'SUCCESS' && (
        <div
          data-testid={`chatbi-answer-${idx}`}
          className="max-w-full space-y-2 rounded-lg border border-gray-200 bg-white p-3 dark:border-gray-700 dark:bg-gray-900"
        >
          <div className="text-xs text-gray-500">
            {a.rowCount ?? a.rows?.length ?? 0} {t('chatbi.rows', undefined, '行')}
            {typeof a.confidence === 'number' && (
              <> · {t('chatbi.confidence', undefined, '置信度')} {(a.confidence * 100).toFixed(0)}%</>
            )}
            {a.llmUsed && <> · {a.llmUsed}</>}
          </div>
          {a.rows && a.rows.length > 0 && (
            <div className="overflow-auto rounded border border-gray-100 dark:border-gray-800">
              <table className="min-w-full text-xs">
                <thead className="bg-gray-50 dark:bg-gray-950">
                  <tr>
                    {cols.map((c) => (
                      <th key={c} className="px-3 py-1.5 text-left font-medium text-gray-600 dark:text-gray-300">
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {a.rows.map((row, ri) => (
                    <tr key={ri} className="odd:bg-white even:bg-gray-50 dark:odd:bg-gray-900 dark:even:bg-gray-950">
                      {cols.map((c) => (
                        <td key={c} className="px-3 py-1 text-gray-700 dark:text-gray-300">
                          {String(row[c] ?? '')}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {a.sql && (
            <details className="text-xs text-gray-400">
              <summary className="cursor-pointer">SQL</summary>
              <pre className="mt-1 overflow-auto rounded bg-gray-50 p-2 dark:bg-gray-950">{a.sql}</pre>
            </details>
          )}
        </div>
      )}
    </div>
  );
}
