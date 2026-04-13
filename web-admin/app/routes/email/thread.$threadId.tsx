/**
 * EmailThreadPage — shows all messages in a Gmail thread.
 *
 * Features:
 *  - Chronological message list with HTML body rendering
 *  - Tracking stats for outbound messages
 *  - Right sidebar: linked CRM records
 *  - "Reply" button → navigate to compose with threadId pre-set
 *  - "Link to CRM" modal (basic)
 */

import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router';
import {
  ArrowLeftIcon,
  PaperAirplaneIcon,
  LinkIcon,
  EnvelopeIcon,
  PaperClipIcon,
  ChevronDownIcon,
  ChevronUpIcon,
} from '@heroicons/react/24/outline';
import {
  getThread,
  getMessageLinks,
  type EmailMessage,
  type EmailThread,
  type CrmLink,
} from '~/services/emailService';
import TrackingStats from '~/components/email/TrackingStats';
import { sanitizeHtml } from '~/meta/utils/sanitizeHtml';

function timeAgo(dateStr: string): string {
  const d = new Date(dateStr);
  return d.toLocaleString();
}

interface MessageCardProps {
  message: EmailMessage;
  defaultExpanded?: boolean;
}

function MessageCard({ message, defaultExpanded = false }: MessageCardProps) {
  const [expanded, setExpanded] = useState(defaultExpanded);

  return (
    <div
      className={`rounded-xl border bg-white dark:bg-gray-800 ${
        message.direction === 'inbound'
          ? 'border-gray-200 dark:border-gray-700'
          : 'border-blue-200 dark:border-blue-800/50'
      }`}
      data-testid={`thread-message-${message.id}`}
    >
      {/* Header */}
      <button
        className="flex w-full items-start gap-4 px-5 py-4 text-start"
        onClick={() => setExpanded((v) => !v)}
      >
        <div className="mt-0.5 flex-shrink-0">
          {message.direction === 'inbound' ? (
            <EnvelopeIcon className="h-5 w-5 text-gray-400" />
          ) : (
            <PaperAirplaneIcon className="h-5 w-5 text-blue-400" />
          )}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <span className="font-medium text-gray-900 dark:text-white">
              {message.direction === 'inbound'
                ? message.fromName || message.fromAddress
                : `Me → ${message.toAddresses.join(', ')}`}
            </span>
            <div className="flex flex-shrink-0 items-center gap-2">
              {message.hasAttachments && (
                <PaperClipIcon className="h-4 w-4 text-gray-400" title="Has attachments" />
              )}
              <span className="text-xs text-gray-400">{timeAgo(message.gmailDate)}</span>
              {expanded ? (
                <ChevronUpIcon className="h-4 w-4 text-gray-400" />
              ) : (
                <ChevronDownIcon className="h-4 w-4 text-gray-400" />
              )}
            </div>
          </div>
          {!expanded && (
            <p className="mt-0.5 truncate text-sm text-gray-500 dark:text-gray-400">
              {message.bodyText?.slice(0, 120) || '(No preview)'}
            </p>
          )}
        </div>
      </button>

      {/* Body */}
      {expanded && (
        <div className="border-t border-gray-100 px-5 pt-4 pb-5 dark:border-gray-700">
          {/* Metadata */}
          <div className="mb-4 space-y-1 text-xs text-gray-500 dark:text-gray-400">
            <div>
              <span className="font-medium">From:</span>{' '}
              {message.fromName
                ? `${message.fromName} <${message.fromAddress}>`
                : message.fromAddress}
            </div>
            <div>
              <span className="font-medium">To:</span> {message.toAddresses.join(', ')}
            </div>
            {message.ccAddresses.length > 0 && (
              <div>
                <span className="font-medium">CC:</span> {message.ccAddresses.join(', ')}
              </div>
            )}
          </div>

          {/* HTML Body */}
          {message.bodyHtml ? (
            <div
              className="prose prose-sm dark:prose-invert max-w-none"
              dangerouslySetInnerHTML={{ __html: sanitizeHtml(message.bodyHtml) }}
            />
          ) : (
            <pre className="font-sans text-sm whitespace-pre-wrap text-gray-700 dark:text-gray-300">
              {message.bodyText || '(Empty)'}
            </pre>
          )}

          {/* Tracking stats for outbound */}
          {message.direction === 'outbound' && (
            <div className="mt-3">
              <TrackingStats messageId={message.id} />
            </div>
          )}

          {/* Attachments */}
          {message.attachments.length > 0 && (
            <div className="mt-4 space-y-1">
              <p className="text-xs font-medium text-gray-500 dark:text-gray-400">Attachments</p>
              {message.attachments.map((att) => (
                <div
                  key={att.attachmentId}
                  className="flex items-center gap-2 rounded-md border border-gray-200 px-3 py-2 text-sm dark:border-gray-700"
                >
                  <PaperClipIcon className="h-4 w-4 text-gray-400" />
                  <span className="text-gray-700 dark:text-gray-300">{att.filename}</span>
                  <span className="text-xs text-gray-400">({(att.size / 1024).toFixed(1)} KB)</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

interface CrmLinksPanelProps {
  messageId: number;
}

function CrmLinksPanel({ messageId }: CrmLinksPanelProps) {
  const [links, setLinks] = useState<CrmLink[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getMessageLinks(messageId)
      .then(setLinks)
      .catch(() => {
        /* non-critical */
      })
      .finally(() => setLoading(false));
  }, [messageId]);

  return (
    <div>
      <h3 className="mb-2 text-sm font-medium text-gray-700 dark:text-gray-300">Linked Records</h3>
      {loading ? (
        <div className="flex justify-center py-4">
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        </div>
      ) : links.length === 0 ? (
        <p className="text-xs text-gray-400 dark:text-gray-500">No CRM records linked</p>
      ) : (
        <ul className="space-y-2">
          {links.map((link) => (
            <li
              key={link.id}
              className="rounded-md border border-gray-200 px-3 py-2 dark:border-gray-700"
            >
              <p className="text-xs font-medium text-gray-700 dark:text-gray-300">
                {link.recordName || `Record #${link.recordId}`}
              </p>
              <p className="text-xs text-gray-400">{link.modelCode}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function EmailThreadPage() {
  const { threadId } = useParams<{ threadId: string }>();
  const navigate = useNavigate();
  const [thread, setThread] = useState<EmailThread | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!threadId) return;
    setLoading(true);
    getThread(threadId)
      .then(setThread)
      .catch(() => console.error('Failed to load thread'))
      .finally(() => setLoading(false));
  }, [threadId]);

  const messages = thread?.messages || [];
  const lastMessage = messages[messages.length - 1];

  return (
    <div className="mx-auto max-w-5xl px-4 py-6" data-testid="email-thread-page">
      {/* Back button */}
      <button
        onClick={() => navigate('/email')}
        className="mb-4 flex items-center gap-1.5 text-sm text-gray-500 transition-colors hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200"
      >
        <ArrowLeftIcon className="h-4 w-4" />
        Back to inbox
      </button>

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        </div>
      ) : !thread || messages.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-gray-400">
          <EnvelopeIcon className="mb-3 h-10 w-10" />
          <p className="text-sm">Thread not found</p>
        </div>
      ) : (
        <div className="flex gap-6">
          {/* Main thread column */}
          <div className="min-w-0 flex-1">
            {/* Thread subject */}
            <h1 className="mb-4 text-xl font-semibold text-gray-900 dark:text-white">
              {messages[0]?.subject || '(No subject)'}
            </h1>

            {/* Messages */}
            <div className="space-y-3">
              {messages.map((msg, idx) => (
                <MessageCard
                  key={msg.id}
                  message={msg}
                  defaultExpanded={idx === messages.length - 1}
                />
              ))}
            </div>

            {/* Reply button */}
            <div className="mt-4">
              <button
                onClick={() => navigate(`/email/compose?threadId=${threadId}`)}
                data-testid="reply-btn"
                className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
              >
                <PaperAirplaneIcon className="h-4 w-4" />
                Reply
              </button>
            </div>
          </div>

          {/* Sidebar */}
          <aside className="w-64 flex-shrink-0">
            <div className="rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
              {/* Link to CRM button */}
              <button
                className="mb-4 flex w-full items-center justify-center gap-2 rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
                data-testid="link-crm-btn"
              >
                <LinkIcon className="h-4 w-4" />
                Link to CRM
              </button>

              {/* Linked records */}
              {lastMessage && <CrmLinksPanel messageId={lastMessage.id} />}
            </div>
          </aside>
        </div>
      )}
    </div>
  );
}
