/**
 * AiField Component
 *
 * An AI-enhanced text field that supports generating, summarizing,
 * translating, classifying, and extracting content using AI.
 */

import React, { useState, useCallback, useRef } from 'react';
import { cn } from '~/utils/cn';
import { ResultHelper } from '~/utils/type';

export type AiOperation = 'generate' | 'summarize' | 'translate' | 'classify' | 'extract';

/**
 * F.3 — vision input on form AiField. Allowed MIME types match what Anthropic
 * Messages API accepts on image content blocks.
 */
const ACCEPTED_IMAGE_MIME_TYPES = 'image/jpeg,image/png,image/gif,image/webp';
/** Hard cap per image — base64 inflates ~33%, keep request body sane. */
const MAX_IMAGE_BYTES = 4 * 1024 * 1024;

export interface AiFieldImageAttachment {
  /** MIME type, e.g. image/png. */
  mediaType: string;
  /** Raw base64 (NO {@code data:} URI prefix). */
  data: string;
  /** Original file name — preview only, not sent to backend. */
  name?: string;
}

function stripDataUriPrefix(dataUri: string): string {
  const idx = dataUri.indexOf(',');
  return idx >= 0 ? dataUri.slice(idx + 1) : dataUri;
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === 'string' ? reader.result : '';
      resolve(stripDataUriPrefix(result));
    };
    reader.onerror = () => reject(reader.error || new Error('FileReader failed'));
    reader.readAsDataURL(file);
  });
}

export interface AiFieldConfig {
  /** Default AI operation */
  operation?: AiOperation;
  /** Custom prompt template */
  prompt?: string;
  /** Source field codes to use as input */
  sourceFields?: string[];
  /** Target language for translation */
  targetLanguage?: string;
  /** Categories for classification */
  categories?: string[];
  /** Fields to extract */
  extractFields?: string[];
  /** Max tokens */
  maxTokens?: number;
  /** Temperature */
  temperature?: number;
}

export interface AiFieldProps {
  /** Form field name for hidden input submission */
  name?: string;
  /** Field label */
  label?: string;
  /** Whether the field is required */
  required?: boolean;
  /** Current field value */
  value?: string;
  /** Callback when value changes */
  onChange?: (value: string) => void;
  /** AI configuration */
  aiConfig?: AiFieldConfig;
  /** Source field values (fieldCode -> value) */
  sourceValues?: Record<string, string>;
  /** Model code for API calls */
  modelCode?: string;
  /** Record ID for API calls */
  recordId?: string;
  /** Whether the field is read-only */
  readOnly?: boolean;
  /** Placeholder text */
  placeholder?: string;
  /** Custom CSS class */
  className?: string;
  /**
   * F.3 — when true, render a paperclip button next to the AI button so the
   * user can attach a screenshot/photo. The selected image is base64-encoded
   * and sent to the inference endpoint as multimodal content. Defaults to
   * false to keep existing AiField call sites byte-identical.
   */
  imageInput?: boolean;
}

const OPERATION_LABELS: Record<AiOperation, { label: string; icon: string }> = {
  generate: { label: 'Generate', icon: 'M' },
  summarize: { label: 'Summarize', icon: 'S' },
  translate: { label: 'Translate', icon: 'T' },
  classify: { label: 'Classify', icon: 'C' },
  extract: { label: 'Extract', icon: 'E' },
};

/**
 * AiField - AI-enhanced text field with generation capabilities
 */
export const AiField: React.FC<AiFieldProps> = ({
  name,
  label,
  required = false,
  value = '',
  onChange,
  aiConfig,
  sourceValues,
  modelCode,
  recordId,
  readOnly = false,
  placeholder = 'AI-generated content will appear here...',
  className,
  imageInput = false,
}) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showOperations, setShowOperations] = useState(false);
  // F.3 — staged image attachments. Cleared on successful generation so the
  // next AI invocation goes back to the text-only path.
  const [attachments, setAttachments] = useState<AiFieldImageAttachment[]>([]);
  const [attachmentError, setAttachmentError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleAttachClick = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const handleFilesSelected = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const files = Array.from(e.target.files ?? []);
      // Reset so picking the same file twice still fires onChange.
      if (e.target) e.target.value = '';
      if (files.length === 0) return;
      setAttachmentError(null);
      const next: AiFieldImageAttachment[] = [];
      for (const f of files) {
        if (f.size > MAX_IMAGE_BYTES) {
          setAttachmentError(`${f.name} 超过 4MB 限制`);
          continue;
        }
        if (!ACCEPTED_IMAGE_MIME_TYPES.split(',').includes(f.type)) {
          setAttachmentError(`${f.name} 格式不支持，仅支持 JPEG/PNG/GIF/WEBP`);
          continue;
        }
        try {
          const data = await readFileAsBase64(f);
          next.push({ mediaType: f.type, data, name: f.name });
        } catch {
          setAttachmentError(`读取 ${f.name} 失败`);
        }
      }
      if (next.length > 0) setAttachments((prev) => [...prev, ...next]);
    },
    [],
  );

  const handleRemoveAttachment = useCallback((idx: number) => {
    setAttachments((prev) => prev.filter((_, i) => i !== idx));
  }, []);

  const handleGenerate = useCallback(
    async (operation?: AiOperation) => {
      const op = operation || aiConfig?.operation || 'generate';
      setLoading(true);
      setError(null);

      try {
        const requestBody: Record<string, unknown> = {
          operation: op,
          prompt: aiConfig?.prompt || '',
          sourceContent: sourceValues || {},
          maxTokens: aiConfig?.maxTokens || 500,
          temperature: aiConfig?.temperature || 0.7,
        };

        // F.3 vision: only emit `images` when there's actually an attachment so
        // the request shape stays identical to the legacy text-only path for
        // unaffected call sites.
        if (attachments.length > 0) {
          requestBody.images = attachments.map((a) => ({
            mediaType: a.mediaType,
            data: a.data,
          }));
        }

        if (op === 'translate' && aiConfig?.targetLanguage) {
          requestBody.targetLanguage = aiConfig.targetLanguage;
        }
        if (op === 'classify' && aiConfig?.categories) {
          requestBody.categories = aiConfig.categories;
        }
        if (op === 'extract' && aiConfig?.extractFields) {
          requestBody.extractFields = aiConfig.extractFields;
        }

        // Use record-specific endpoint if available
        const url =
          modelCode && recordId
            ? `/api/meta/ai/models/${modelCode}/records/${recordId}/ai-fill`
            : '/api/meta/ai/generate';

        if (modelCode && recordId) {
          (requestBody as Record<string, unknown>).fieldCode = 'ai_field';
        }

        const response = await fetch(url, {
          method: 'post',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(requestBody),
        });

        if (!response.ok) {
          throw new Error(`AI service returned ${response.status}`);
        }

        const result = await response.json();
        if (ResultHelper.isSuccess(result) && result.data?.content) {
          onChange?.(result.data.content);
          // F.3 — successful inference takes ownership of the staged images;
          // clear them so a follow-up plain-text AI call doesn't re-send the
          // same screenshot.
          setAttachments([]);
        } else if (result.data?.error) {
          setError(result.data.error);
        } else {
          setError('Failed to generate content');
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'AI generation failed');
      } finally {
        setLoading(false);
        setShowOperations(false);
      }
    },
    [aiConfig, sourceValues, modelCode, recordId, onChange, attachments],
  );

  if (readOnly) {
    return (
      <div className={className}>
        {label && (
          <label className="mb-1 block text-sm font-medium text-gray-700">
            {label}
            {required && <span className="ml-0.5 text-red-500">*</span>}
          </label>
        )}
        {name && <input type="hidden" name={name} value={value} />}
        <div className="text-sm text-gray-700">
          {value || <span className="text-gray-400">-</span>}
        </div>
      </div>
    );
  }

  return (
    <div className={className}>
      {label && (
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {label}
          {required && <span className="ml-0.5 text-red-500">*</span>}
        </label>
      )}
      {name && <input type="hidden" name={name} value={value} />}
      <div className="relative">
      {/* Text area */}
      <textarea
        value={value}
        onChange={(e) => onChange?.(e.target.value)}
        placeholder={placeholder}
        rows={3}
        className={cn(
          'w-full resize-y rounded-md border border-gray-300 px-3 py-2 text-sm',
          'focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none',
          'placeholder:text-gray-400',
          loading && 'opacity-50',
        )}
        disabled={loading}
      />

      {/* F.3 — staged image preview (rendered only when at least one image attached). */}
      {imageInput && attachments.length > 0 && (
        <div
          className="mt-1.5 flex flex-wrap gap-2"
          data-testid="aifield-attachments-preview"
        >
          {attachments.map((att, idx) => (
            <div
              key={`${att.name || 'image'}-${idx}`}
              className="flex items-center gap-1.5 rounded border border-gray-200 bg-gray-50 px-2 py-1 text-xs"
              data-testid={`aifield-attachment-${idx}`}
            >
              <img
                src={`data:${att.mediaType};base64,${att.data}`}
                alt={att.name || `attachment-${idx}`}
                className="h-8 w-8 rounded object-cover"
              />
              <span className="max-w-[120px] truncate">{att.name || `image-${idx + 1}`}</span>
              <button
                type="button"
                onClick={() => handleRemoveAttachment(idx)}
                title="Remove image"
                data-testid={`aifield-attachment-remove-${idx}`}
                className="text-gray-400 hover:text-red-500"
              >
                ×
              </button>
            </div>
          ))}
        </div>
      )}
      {imageInput && attachmentError && (
        <div
          className="mt-1.5 text-xs text-red-500"
          data-testid="aifield-attachment-error"
        >
          {attachmentError}
        </div>
      )}

      {/* AI action bar */}
      <div className="mt-1.5 flex items-center justify-between gap-2">
        <div className="flex items-center gap-1">
          {/* Main AI button */}
          <button
            type="button"
            onClick={() => handleGenerate()}
            disabled={loading}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-md px-3 py-1 text-xs font-medium',
              'bg-gradient-to-r from-purple-500 to-blue-500 text-white',
              'hover:from-purple-600 hover:to-blue-600',
              'disabled:cursor-not-allowed disabled:opacity-50',
              'transition-all duration-150',
            )}
          >
            {loading ? (
              <>
                <span className="h-3 w-3 animate-spin rounded-full border-2 border-white/30 border-t-white" />
                Generating...
              </>
            ) : (
              <>
                <svg
                  className="h-3.5 w-3.5"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.455 2.456L21.75 6l-1.036.259a3.375 3.375 0 00-2.455 2.456z"
                  />
                </svg>
                AI {OPERATION_LABELS[aiConfig?.operation || 'generate'].label}
              </>
            )}
          </button>

          {/* F.3 — image attachment trigger (opt-in via `imageInput` prop). */}
          {imageInput && (
            <>
              <input
                ref={fileInputRef}
                type="file"
                accept={ACCEPTED_IMAGE_MIME_TYPES}
                multiple
                className="hidden"
                data-testid="aifield-image-input"
                onChange={handleFilesSelected}
              />
              <button
                type="button"
                onClick={handleAttachClick}
                disabled={loading}
                title="Attach image"
                data-testid="aifield-attach-image"
                className={cn(
                  'rounded p-1 text-gray-400 hover:text-gray-600',
                  'disabled:cursor-not-allowed disabled:opacity-50',
                )}
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13"
                  />
                </svg>
              </button>
            </>
          )}

          {/* More operations dropdown */}
          <div className="relative">
            <button
              type="button"
              onClick={() => setShowOperations(!showOperations)}
              disabled={loading}
              className={cn(
                'rounded p-1 text-gray-400 hover:text-gray-600',
                'disabled:cursor-not-allowed disabled:opacity-50',
              )}
              title="More AI operations"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M19 9l-7 7-7-7"
                />
              </svg>
            </button>

            {showOperations && (
              <div className="absolute bottom-full left-0 z-10 mb-1 min-w-[160px] rounded-md border border-gray-200 bg-white shadow-lg">
                {(
                  Object.entries(OPERATION_LABELS) as [
                    AiOperation,
                    { label: string; icon: string },
                  ][]
                ).map(([op, config]) => (
                  <button
                    key={op}
                    type="button"
                    onClick={() => handleGenerate(op)}
                    className={cn(
                      'flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs hover:bg-gray-50',
                      aiConfig?.operation === op && 'bg-blue-50 text-blue-700',
                    )}
                  >
                    <span className="flex h-5 w-5 items-center justify-center rounded bg-purple-100 text-[10px] font-bold text-purple-600">
                      {config.icon}
                    </span>
                    {config.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Token count or error */}
        {error && (
          <span className="max-w-[200px] truncate text-xs text-red-500" title={error}>
            {error}
          </span>
        )}
      </div>
      </div>
    </div>
  );
};

export default AiField;
