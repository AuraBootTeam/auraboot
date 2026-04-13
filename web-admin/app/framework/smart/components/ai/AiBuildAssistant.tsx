/**
 * AiBuildAssistant Component
 *
 * A conversational AI assistant that helps users build data models
 * from natural language descriptions.
 */

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { cn } from '~/utils/cn';
import { ResultHelper } from '~/utils/type';

interface FieldSuggestion {
  fieldCode: string;
  fieldName: string;
  dataType: string;
  required: boolean;
  description: string;
}

interface ModelSuggestion {
  modelCode: string;
  modelName: string;
  description: string;
  fields: FieldSuggestion[];
  suggestedViews: string[];
}

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  suggestion?: ModelSuggestion;
  timestamp: Date;
}

export interface AiBuildAssistantProps {
  /** Callback when user confirms model creation */
  onCreateModel?: (suggestion: ModelSuggestion) => void;
  /** Whether the assistant panel is open */
  open?: boolean;
  /** Callback to close the assistant */
  onClose?: () => void;
  /** Custom CSS class */
  className?: string;
}

const DATA_TYPE_LABELS: Record<string, string> = {
  STRING: 'String',
  INTEGER: 'Integer',
  DECIMAL: 'Decimal',
  DATE: 'Date',
  DATETIME: 'DateTime',
  BOOLEAN: 'Boolean',
  TEXT: 'Text',
  ENUM: 'Enum',
  JSON: 'json',
  REFERENCE: 'Reference',
  COMPUTED: 'Computed',
};

/**
 * AiBuildAssistant - Conversational AI model builder
 */
export const AiBuildAssistant: React.FC<AiBuildAssistantProps> = ({
  onCreateModel,
  open = true,
  onClose,
  className,
}) => {
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      role: 'assistant',
      content:
        'Hi! Describe your business scenario and I\'ll help you design a data model. For example: "Customer management system with contacts, orders, and follow-up records."',
      timestamp: new Date(),
    },
  ]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const chatEndRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSend = useCallback(async () => {
    const text = input.trim();
    if (!text || loading) return;

    const userMessage: ChatMessage = {
      role: 'user',
      content: text,
      timestamp: new Date(),
    };

    setMessages((prev) => [...prev, userMessage]);
    setInput('');
    setLoading(true);

    try {
      const response = await fetch('/api/meta/ai/suggest-model', {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ description: text }),
      });

      if (!response.ok) {
        throw new Error(`Request failed: ${response.status}`);
      }

      const result = await response.json();

      if (ResultHelper.isSuccess(result) && result.data) {
        const suggestion = result.data as ModelSuggestion;
        const assistantMessage: ChatMessage = {
          role: 'assistant',
          content: `Based on your description, here's a suggested model structure for "${suggestion.modelName}":`,
          suggestion,
          timestamp: new Date(),
        };
        setMessages((prev) => [...prev, assistantMessage]);
      } else {
        setMessages((prev) => [
          ...prev,
          {
            role: 'assistant',
            content:
              "Sorry, I couldn't generate a model suggestion. Please try describing your scenario in more detail.",
            timestamp: new Date(),
          },
        ]);
      }
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: `Error: ${err instanceof Error ? err.message : 'Failed to connect to AI service'}. Please try again.`,
          timestamp: new Date(),
        },
      ]);
    } finally {
      setLoading(false);
    }
  }, [input, loading]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend],
  );

  const handleCreateFromSuggestion = useCallback(
    (suggestion: ModelSuggestion) => {
      onCreateModel?.(suggestion);
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: `Model "${suggestion.modelName}" creation initiated! You can customize it further in the model editor.`,
          timestamp: new Date(),
        },
      ]);
    },
    [onCreateModel],
  );

  if (!open) return null;

  return (
    <div
      className={cn(
        'flex flex-col rounded-lg border border-gray-200 bg-white shadow-lg',
        'h-[600px] w-full max-w-lg',
        className,
      )}
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-gradient-to-r from-purple-50 to-blue-50 px-4 py-3">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-r from-purple-500 to-blue-500">
            <svg
              className="h-5 w-5 text-white"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09z"
              />
            </svg>
          </div>
          <div>
            <h3 className="text-sm font-semibold text-gray-900">AI Build Assistant</h3>
            <p className="text-xs text-gray-500">Describe your scenario to generate a model</p>
          </div>
        </div>
        {onClose && (
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        )}
      </div>

      {/* Chat Messages */}
      <div className="flex-1 space-y-4 overflow-y-auto px-4 py-3">
        {messages.map((msg, i) => (
          <div
            key={i}
            className={cn('flex', msg.role === 'user' ? 'justify-end' : 'justify-start')}
          >
            <div
              className={cn(
                'max-w-[85%] rounded-lg px-3 py-2 text-sm',
                msg.role === 'user' ? 'bg-blue-500 text-white' : 'bg-gray-100 text-gray-800',
              )}
            >
              <p className="whitespace-pre-wrap">{msg.content}</p>

              {/* Model Suggestion Card */}
              {msg.suggestion && (
                <div className="mt-3 overflow-hidden rounded-lg border border-gray-200 bg-white">
                  <div className="border-b border-gray-200 bg-gray-50 px-3 py-2">
                    <div className="text-sm font-medium text-gray-900">
                      {msg.suggestion.modelName}
                    </div>
                    <div className="mt-0.5 text-xs text-gray-500">
                      Code: {msg.suggestion.modelCode}
                    </div>
                  </div>

                  {/* Fields */}
                  <div className="divide-y divide-gray-100">
                    {msg.suggestion.fields.map((field, fi) => (
                      <div
                        key={fi}
                        className="flex items-center justify-between px-3 py-1.5 text-xs"
                      >
                        <div className="flex items-center gap-1.5">
                          <span className="font-medium text-gray-700">{field.fieldName}</span>
                          {field.required && <span className="text-red-500">*</span>}
                        </div>
                        <span className="rounded bg-blue-50 px-1.5 py-0.5 text-[10px] text-blue-600">
                          {DATA_TYPE_LABELS[field.dataType] || field.dataType}
                        </span>
                      </div>
                    ))}
                  </div>

                  {/* Suggested views */}
                  {msg.suggestion.suggestedViews && msg.suggestion.suggestedViews.length > 0 && (
                    <div className="flex items-center gap-1 border-t border-gray-100 px-3 py-1.5">
                      <span className="text-[10px] text-gray-500">Views:</span>
                      {msg.suggestion.suggestedViews.map((v) => (
                        <span
                          key={v}
                          className="rounded bg-purple-50 px-1.5 py-0.5 text-[10px] text-purple-600"
                        >
                          {v}
                        </span>
                      ))}
                    </div>
                  )}

                  {/* Create button */}
                  <div className="border-t border-gray-200 bg-gray-50 px-3 py-2">
                    <button
                      type="button"
                      onClick={() => handleCreateFromSuggestion(msg.suggestion!)}
                      className={cn(
                        'w-full rounded-md px-3 py-1.5 text-xs font-medium',
                        'bg-gradient-to-r from-purple-500 to-blue-500 text-white',
                        'hover:from-purple-600 hover:to-blue-600',
                        'transition-all duration-150',
                      )}
                    >
                      Create This Model
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        ))}

        {loading && (
          <div className="flex justify-start">
            <div className="rounded-lg bg-gray-100 px-4 py-3 text-sm text-gray-500">
              <div className="flex items-center gap-2">
                <span className="h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-purple-500" />
                Analyzing your scenario...
              </div>
            </div>
          </div>
        )}

        <div ref={chatEndRef} />
      </div>

      {/* Input */}
      <div className="border-t border-gray-200 px-4 py-3">
        <div className="flex items-end gap-2">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Describe your business scenario..."
            rows={1}
            className={cn(
              'flex-1 resize-none rounded-lg border border-gray-300 px-3 py-2 text-sm',
              'focus:border-purple-500 focus:ring-2 focus:ring-purple-500 focus:outline-none',
              'placeholder:text-gray-400',
              'max-h-[80px]',
            )}
            disabled={loading}
          />
          <button
            type="button"
            onClick={handleSend}
            disabled={!input.trim() || loading}
            className={cn(
              'rounded-lg p-2',
              'bg-gradient-to-r from-purple-500 to-blue-500 text-white',
              'hover:from-purple-600 hover:to-blue-600',
              'disabled:cursor-not-allowed disabled:opacity-50',
              'transition-all duration-150',
            )}
          >
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8"
              />
            </svg>
          </button>
        </div>
      </div>
    </div>
  );
};

export default AiBuildAssistant;
