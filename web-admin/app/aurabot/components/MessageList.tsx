import { useEffect, useRef, type ReactElement } from 'react';
import type { Message } from '../types/envelope';
import type { SkillSuggestion } from '../types/skill';
import { EnvelopeRouter } from './envelopes/EnvelopeRouter';

export interface MessageListProps {
  messages: Message[];
  onConfirm?: (previewToken: string) => void;
  onCancel?: (previewToken: string) => void;
  onSuggestionPick?: (suggestion: SkillSuggestion) => void;
}

export function MessageList({
  messages,
  onConfirm,
  onCancel,
  onSuggestionPick,
}: MessageListProps): ReactElement {
  const bottomRef = useRef<HTMLDivElement | null>(null);

  // Auto-scroll new messages into view on append.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages.length]);

  return (
    <div
      data-aurabot-message-list
      className="flex-1 overflow-y-auto px-4 py-3"
      role="log"
      aria-live="polite"
    >
      <ul className="flex flex-col gap-3">
        {messages.map((msg) => (
          <li
            key={msg.id}
            data-aurabot-message-id={msg.id}
            data-aurabot-message-role={msg.role}
            className={
              msg.role === 'user'
                ? 'flex flex-col items-end gap-1'
                : 'flex flex-col items-start gap-1'
            }
          >
            <div
              className={
                msg.role === 'user'
                  ? 'max-w-[85%] rounded-lg bg-blue-600 px-3 py-2 text-sm text-white'
                  : 'flex w-full max-w-[85%] flex-col gap-2'
              }
            >
              {msg.envelopes.map((env, i) => (
                <EnvelopeRouter
                  key={`${msg.id}-${i}`}
                  envelope={env}
                  onConfirm={onConfirm}
                  onCancel={onCancel}
                  onSuggestionPick={onSuggestionPick}
                />
              ))}
            </div>
          </li>
        ))}
      </ul>
      <div ref={bottomRef} />
    </div>
  );
}
