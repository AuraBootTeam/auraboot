/**
 * EmailTimeline — shows emails linked to a business record.
 *
 * Props:
 *  - modelCode: the model code of the parent record (e.g., 'customer-account')
 *  - recordPid: the public pid of the record
 *
 * Fetches via getMessagesByRecord, renders a timeline with:
 *  - Direction icon (inbound ↓ / outbound ↑)
 *  - From/to address, subject, timestamp
 *  - Snippet of body text (first 100 chars)
 *  - Click to expand full body
 *  - Tracking stats for outbound messages
 */

import { useState, useEffect } from 'react';
import {
  EnvelopeIcon,
  PaperAirplaneIcon,
  ChevronDownIcon,
  ChevronUpIcon,
} from '@heroicons/react/24/outline';
import { getMessagesByRecord, type EmailMessage } from '~/shared/services/emailService';
import TrackingStats from './TrackingStats';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';
import { useI18n } from '~/contexts/I18nContext';
import { useSmartText } from '~/utils/i18n';
import type { BlockRendererProps } from '@auraboot/runtime-kernel';

interface EmailTimelineProps {
  modelCode: string;
  recordPid: string;
}

export function RecordEmailTimeline({ runtime }: BlockRendererProps) {
  const { modelCode, recordPid } = runtime.getContext().$page ?? {};
  if (typeof modelCode !== 'string' || typeof recordPid !== 'string' || !recordPid) return null;
  return <EmailTimeline key={`${modelCode}:${recordPid}`} modelCode={modelCode} recordPid={recordPid} />;
}

interface EmailTimelineItemProps {
  message: EmailMessage;
}

function EmailTimelineItem({ message }: EmailTimelineItemProps) {
  const st = useSmartText();
  const { locale } = useI18n();
  const [expanded, setExpanded] = useState(false);
  const isInbound = message.direction === 'inbound';
  const sentAt = message.gmailDate ? new Date(message.gmailDate).toLocaleString(locale) : '';

  const snippet = message.bodyText
    ? message.bodyText.slice(0, 100) + (message.bodyText.length > 100 ? '…' : '')
    : '';

  return (
    <div className="flex gap-3" data-testid={`email-timeline-item-${message.id}`}>
      {/* Icon column */}
      <div className="flex flex-col items-center">
        <div
          className={`flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full ${
            isInbound ? 'bg-gray-100 dark:bg-gray-700' : 'bg-blue-100 dark:bg-blue-900/30'
          }`}
          title={isInbound ? st({ 'zh-CN': '收件', en: 'Inbound' }) : st({ 'zh-CN': '发件', en: 'Outbound' })}
        >
          {isInbound ? (
            <EnvelopeIcon className="h-4 w-4 text-gray-500 dark:text-gray-400" />
          ) : (
            <PaperAirplaneIcon className="h-4 w-4 text-blue-500" />
          )}
        </div>
        <div className="mt-1 flex-1 border-l border-gray-200 dark:border-gray-700" />
      </div>

      {/* Content */}
      <div className="min-w-0 flex-1 pb-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="text-xs font-medium text-gray-500 dark:text-gray-400">
              {isInbound
                ? message.fromName
                  ? `${message.fromName} <${message.fromAddress}>`
                  : message.fromAddress
                : `${st({ 'zh-CN': '收件人', en: 'To' })}: ${message.toAddresses?.join(', ') ?? ''}`}
            </p>
            <p className="mt-0.5 text-sm font-medium text-gray-900 dark:text-white">
              {message.subject || st({ 'zh-CN': '无主题', en: 'No subject' })}
            </p>
          </div>
          <span className="flex-shrink-0 text-xs text-gray-400">{sentAt}</span>
        </div>

        {/* Snippet / Expand */}
        {(snippet || message.bodyHtml) && (
          <>
            {expanded && (
              message.bodyText
                ? <p className="text-text-2 whitespace-pre-wrap break-words text-sm">{message.bodyText}</p>
                : <div className="prose prose-sm max-w-none"
                    dangerouslySetInnerHTML={{ __html: sanitizeHtml(message.bodyHtml ?? '', { allowImages: false }) }} />
            )}
            <button type="button" aria-expanded={expanded} onClick={() => setExpanded((v) => !v)}
              className="text-accent mt-1 flex items-center gap-1 text-start text-sm">
              {expanded
                ? <>{st({ 'zh-CN': '收起正文', en: 'Hide message' })}<ChevronUpIcon className="h-3 w-3" /></>
                : <>{snippet || st({ 'zh-CN': '展开正文', en: 'Show message' })}<ChevronDownIcon className="h-3 w-3 shrink-0" /></>}
            </button>
          </>
        )}

        {/* Tracking stats for outbound */}
        {!isInbound && (
          <div className="mt-2">
            <TrackingStats messageId={message.id} />
          </div>
        )}
      </div>
    </div>
  );
}

export default function EmailTimeline({ modelCode, recordPid }: EmailTimelineProps) {
  const st = useSmartText();
  const [messages, setMessages] = useState<EmailMessage[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    getMessagesByRecord(modelCode, recordPid, page)
      .then((records) => {
        if (cancelled) return;
        setMessages((previous) => page === 1 ? records : [
          ...previous, ...records.filter((record) => !previous.some((saved) => saved.id === record.id)),
        ]);
        setHasMore(records.length === 10);
      })
      .catch(() => { if (!cancelled) setError(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [modelCode, recordPid, page, attempt]);

  if (loading && !messages.length) {
    return (
      <div className="flex items-center justify-center py-6" data-testid="email-timeline-loading">
        <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  if (!error && messages.length === 0) {
    return (
      <div
        className="flex flex-col items-center justify-center py-8 text-center"
        data-testid="email-timeline-empty"
      >
        <EnvelopeIcon className="mb-2 h-8 w-8 text-gray-300 dark:text-gray-600" />
        <p className="text-text-2 text-sm">{st({ 'zh-CN': '暂无关联邮件', en: 'No linked emails' })}</p>
      </div>
    );
  }

  return (
    <div className="space-y-0" data-testid={`email-timeline-${modelCode}-${recordPid}`}>
      {messages.map((message) => (
        <EmailTimelineItem key={message.id} message={message} />
      ))}
      {error ? (
        <div role="alert" className="text-text-2 text-sm" data-testid="email-timeline-error">
          {st({ 'zh-CN': '无法加载关联邮件。', en: 'Unable to load linked emails.' })}
          <button type="button" onClick={() => setAttempt((value) => value + 1)} className="text-accent ms-2">
            {st({ 'zh-CN': '重试', en: 'Retry' })}
          </button>
        </div>
      ) : hasMore && (
        <button type="button" disabled={loading} onClick={() => setPage((value) => value + 1)}
          className="text-accent text-sm">
          {st({ 'zh-CN': '加载更多', en: 'Load more' })}
        </button>
      )}
    </div>
  );
}
