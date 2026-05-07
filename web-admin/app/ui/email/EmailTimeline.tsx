/**
 * EmailTimeline — shows emails linked to a CRM record.
 *
 * Props:
 *  - modelCode: the model code of the parent record (e.g., 'crm-contact')
 *  - recordId: the numeric ID of the record
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

interface EmailTimelineProps {
  modelCode: string;
  recordId: number;
}

function timeAgo(dateStr: string): string {
  const now = Date.now();
  const diff = now - new Date(dateStr).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(dateStr).toLocaleDateString();
}

interface EmailTimelineItemProps {
  message: EmailMessage;
}

function EmailTimelineItem({ message }: EmailTimelineItemProps) {
  const [expanded, setExpanded] = useState(false);
  const isInbound = message.direction === 'inbound';

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
          title={isInbound ? 'Inbound' : 'Outbound'}
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
                : `To: ${message.toAddresses.join(', ')}`}
            </p>
            <p className="mt-0.5 text-sm font-medium text-gray-900 dark:text-white">
              {message.subject || '(No subject)'}
            </p>
          </div>
          <span className="flex-shrink-0 text-xs text-gray-400">{timeAgo(message.gmailDate)}</span>
        </div>

        {/* Snippet / Expand */}
        {snippet && (
          <button onClick={() => setExpanded((v) => !v)} className="mt-1 w-full text-start">
            {!expanded ? (
              <p className="flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
                {snippet}
                <ChevronDownIcon className="inline h-3 w-3 flex-shrink-0" />
              </p>
            ) : (
              <div>
                <div className="text-xs text-gray-700 dark:text-gray-300">
                  {message.bodyHtml ? (
                    <div
                      className="prose prose-xs dark:prose-invert max-w-none"
                      dangerouslySetInnerHTML={{ __html: sanitizeHtml(message.bodyHtml) }}
                    />
                  ) : (
                    <pre className="font-sans whitespace-pre-wrap">{message.bodyText}</pre>
                  )}
                </div>
                <span className="mt-1 flex items-center gap-1 text-xs text-blue-500">
                  Show less <ChevronUpIcon className="inline h-3 w-3" />
                </span>
              </div>
            )}
          </button>
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

export default function EmailTimeline({ modelCode, recordId }: EmailTimelineProps) {
  const [messages, setMessages] = useState<EmailMessage[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    getMessagesByRecord(modelCode, recordId)
      .then(setMessages)
      .catch(() => {
        /* non-critical */
      })
      .finally(() => setLoading(false));
  }, [modelCode, recordId]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-6" data-testid="email-timeline-loading">
        <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  if (messages.length === 0) {
    return (
      <div
        className="flex flex-col items-center justify-center py-8 text-center"
        data-testid="email-timeline-empty"
      >
        <EnvelopeIcon className="mb-2 h-8 w-8 text-gray-300 dark:text-gray-600" />
        <p className="text-sm text-gray-400 dark:text-gray-500">No emails linked</p>
      </div>
    );
  }

  return (
    <div className="space-y-0" data-testid={`email-timeline-${modelCode}-${recordId}`}>
      {messages.map((message) => (
        <EmailTimelineItem key={message.id} message={message} />
      ))}
    </div>
  );
}
