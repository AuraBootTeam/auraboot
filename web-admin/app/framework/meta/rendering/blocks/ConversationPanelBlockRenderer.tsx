/**
 * ConversationPanelBlockRenderer — a live two-way conversation, driven entirely by DSL config.
 *
 * A workbench page puts a queue table on the left and this on the right: pick a row, read what has
 * been said, say something back, and see new messages arrive without asking. That last part is why
 * this is a block and not a table — nothing else in the DSL can render something that arrives on its
 * own.
 *
 * It knows nothing about customer service. The endpoints are configuration, so the same block serves
 * any "watch a conversation and reply to it" surface — a CS seat console today, an IM workbench or an
 * agent-run transcript later — without a second component drifting out of sync with this one.
 *
 * DSL config:
 * {
 *   "blockType": "conversation-panel",
 *   "title": "对话",
 *   "sessionKey": "selectedSession",                                  // page-state key holding the id
 *   "transcriptEndpoint": "/api/cs/seat/sessions/{session}/messages", // GET  -> CsTranscriptMessage[]
 *   "streamEndpoint":     "/api/cs/seat/sessions/{session}/stream",   // GET  -> SSE
 *   "replyEndpoint":      "/api/cs/seat/sessions/{session}/reply",    // POST { message }
 *   "canReply": "{{ selectedState === 'human_active' }}",             // optional guard expression
 *   "emptyText": "选择一个会话"
 * }
 *
 * `{session}` in any endpoint is replaced by the current value of `sessionKey`. When that key is
 * empty the panel renders its empty state and opens no connections: a stream to a conversation
 * nobody selected is a socket held open for nothing.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Loader2, Send } from 'lucide-react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { useI18n } from '~/shared/i18n';

export interface ConversationPanelBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

interface TranscriptLine {
  seq: number | null;
  senderType: string;
  senderName?: string | null;
  content: string;
  at?: string;
}

/** A line the server sent us but that we have already got. Keyed on seq, which is per-conversation. */
function dedupe(lines: TranscriptLine[]): TranscriptLine[] {
  const seen = new Set<string>();
  const out: TranscriptLine[] = [];
  for (const line of lines) {
    // seq is null for a message the server pushed before it had one (a streamed AI answer). Those
    // cannot be deduped by key, so they are kept — a duplicate is better than a missing answer.
    const key = line.seq === null || line.seq === undefined ? '' : String(line.seq);
    if (key && seen.has(key)) continue;
    if (key) seen.add(key);
    out.push(line);
  }
  return out;
}

export function ConversationPanelBlockRenderer({ block, runtime }: ConversationPanelBlockRendererProps) {
  const { t } = useI18n();
  const cfg = block as unknown as Record<string, any>;

  const sessionKey: string = cfg.sessionKey || 'selectedSession';
  const emptyText: string = cfg.emptyText || t('cs.panel.empty', '选择一个会话查看内容');

  const stateManager = runtime.getStateManager();
  const scopeId = runtime.getScopeId();

  const [session, setSession] = useState<string | null>(
    () => stateManager.getContext(scopeId)?.state?.[sessionKey] ?? null,
  );
  const [lines, setLines] = useState<TranscriptLine[]>([]);
  const [loading, setLoading] = useState(false);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const bottomRef = useRef<HTMLDivElement | null>(null);

  // Follow the page's selection. The queue table writes the pid into page state; this reads it.
  useEffect(() => {
    const unsubscribe = stateManager.subscribe(scopeId, () => {
      const next = stateManager.getContext(scopeId)?.state?.[sessionKey] ?? null;
      setSession((current) => (current === next ? current : next));
    });
    return unsubscribe;
  }, [stateManager, scopeId, sessionKey]);

  const url = useCallback(
    (template?: string) => (template && session ? template.replace('{session}', session) : null),
    [session],
  );

  // --- history -------------------------------------------------------------
  useEffect(() => {
    const endpoint = url(cfg.transcriptEndpoint);
    if (!endpoint) {
      setLines([]);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    fetch(endpoint, { credentials: 'include' })
      .then((res) => (res.ok ? res.json() : Promise.reject(new Error(String(res.status)))))
      .then((body) => {
        if (cancelled) return;
        // Tolerates both the platform envelope ({code,data}) and a bare array, because a block that
        // works on one endpoint shape and silently renders nothing on the other is a trap.
        const data = Array.isArray(body) ? body : body?.data;
        setLines(dedupe(Array.isArray(data) ? data : []));
      })
      .catch((e) => {
        if (!cancelled) setError(String(e?.message || e));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [url, cfg.transcriptEndpoint]);

  // --- live stream ---------------------------------------------------------
  useEffect(() => {
    const endpoint = url(cfg.streamEndpoint);
    if (!endpoint) return;

    const source = new EventSource(endpoint, { withCredentials: true });

    const onMessage = (event: MessageEvent) => {
      try {
        const payload = JSON.parse(event.data);
        setLines((current) =>
          dedupe([
            ...current,
            {
              seq: payload.seq ?? null,
              senderType: payload.senderType,
              senderName: payload.senderName,
              content: payload.content,
              at: payload.at,
            },
          ]),
        );
      } catch {
        // A frame we cannot parse is a frame we drop. It is not worth tearing the panel down for.
      }
    };

    source.addEventListener('message', onMessage as EventListener);
    // A state change (queued / taken / closed) is not a line of the transcript, but it does change
    // what the seat may do — so it goes back through page state, where the action bar reads it.
    source.addEventListener('state', ((event: MessageEvent) => {
      try {
        const payload = JSON.parse(event.data);
        stateManager.updateState(scopeId, 'selectedState', payload.state);
      } catch {
        /* ignore */
      }
    }) as EventListener);

    return () => source.close();
  }, [url, cfg.streamEndpoint, stateManager, scopeId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [lines.length]);

  // --- replying ------------------------------------------------------------
  const send = useCallback(async () => {
    const endpoint = url(cfg.replyEndpoint);
    if (!endpoint || !draft.trim() || sending) return;

    setSending(true);
    setError(null);
    try {
      const res = await fetch(endpoint, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: draft.trim() }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        // The business reason, not a generic "request failed" — the seat needs to know that the
        // session was closed under them, not merely that something went wrong.
        throw new Error(body?.context || body?.message || String(res.status));
      }
      setDraft('');
      // The reply comes back to us on the stream we are already holding, so it is not appended here:
      // doing both would show it twice.
    } catch (e: any) {
      setError(String(e?.message || e));
    } finally {
      setSending(false);
    }
  }, [url, cfg.replyEndpoint, draft, sending]);

  const canReply = useMemo(() => {
    if (!session || !cfg.replyEndpoint) return false;
    if (!cfg.canReply) return true;
    try {
      return Boolean(runtime.getEvaluator().evaluate(cfg.canReply, runtime.getContext()));
    } catch {
      return false;
    }
  }, [session, cfg.replyEndpoint, cfg.canReply, runtime]);

  if (!session) {
    return (
      <div
        data-testid="conversation-panel-empty"
        className="flex h-full min-h-[320px] items-center justify-center rounded-lg border border-border bg-muted/30 text-sm text-muted-foreground"
      >
        {emptyText}
      </div>
    );
  }

  return (
    <div
      data-testid="conversation-panel"
      className="flex h-full min-h-[420px] flex-col overflow-hidden rounded-lg border border-border bg-background"
    >
      {cfg.title && (
        <div className="border-b border-border px-4 py-2 text-sm font-medium">{cfg.title}</div>
      )}

      <div className="flex-1 space-y-3 overflow-y-auto p-4" data-testid="conversation-panel-messages">
        {loading && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
            {t('common.loading', '加载中')}
          </div>
        )}

        {!loading && lines.length === 0 && (
          <div className="text-sm text-muted-foreground" data-testid="conversation-panel-no-messages">
            {t('cs.panel.noMessages', '还没有消息')}
          </div>
        )}

        {lines.map((line, index) => {
          const mine = line.senderType === 'human';
          return (
            <div
              key={`${line.seq ?? 'x'}-${index}`}
              data-testid={`conversation-message-${line.senderType}`}
              className={`flex ${mine ? 'justify-end' : 'justify-start'}`}
            >
              <div
                className={`max-w-[80%] rounded-lg px-3 py-2 text-sm ${
                  mine
                    ? 'bg-primary text-primary-foreground'
                    : line.senderType === 'visitor'
                      ? 'bg-muted text-foreground'
                      : 'border border-border bg-background text-foreground'
                }`}
              >
                <div className="mb-0.5 text-[11px] opacity-70">
                  {line.senderType === 'visitor'
                    ? t('cs.panel.visitor', '访客')
                    : line.senderType === 'human'
                      ? line.senderName || t('cs.panel.agent', '客服')
                      : t('cs.panel.ai', 'AI')}
                </div>
                <div className="whitespace-pre-wrap break-words">{line.content}</div>
              </div>
            </div>
          );
        })}
        <div ref={bottomRef} />
      </div>

      {error && (
        <div className="border-t border-border bg-destructive/10 px-4 py-2 text-xs text-destructive"
             data-testid="conversation-panel-error">
          {error}
        </div>
      )}

      <div className="flex items-center gap-2 border-t border-border p-3">
        <input
          type="text"
          value={draft}
          disabled={!canReply || sending}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              void send();
            }
          }}
          data-testid="conversation-panel-input"
          placeholder={
            canReply
              ? t('cs.panel.placeholder', '输入回复,回车发送')
              : t('cs.panel.cannotReply', '接单后才能回复')
          }
          className="h-9 flex-1 rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
        />
        <button
          type="button"
          onClick={() => void send()}
          disabled={!canReply || sending || !draft.trim()}
          data-testid="conversation-panel-send"
          className="inline-flex h-9 items-center gap-1.5 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {sending ? (
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
          ) : (
            <Send className="h-4 w-4" aria-hidden="true" />
          )}
          {t('cs.panel.send', '发送')}
        </button>
      </div>
    </div>
  );
}

export default ConversationPanelBlockRenderer;
