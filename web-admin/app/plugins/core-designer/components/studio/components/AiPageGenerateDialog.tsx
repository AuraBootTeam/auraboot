/**
 * AiPageGenerateDialog — Natural language → Page DSL generation
 *
 * Uses AuraBot chat/stream API with a specialized system prompt
 * to generate page layouts from user descriptions.
 *
 * @since 4.1.0
 */

import React, { useState, useRef } from 'react';
import { auraBotApi } from '~/plugins/core-aurabot/services/auraBotApi';
import { buildPageGenerationPrompt, parsePageDslResponse } from './ai-page-prompt';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';

export interface AiPageGenerateDialogProps {
  open: boolean;
  onClose: () => void;
  onGenerated: (dsl: { kind: PageSchema['kind']; blocks: PageSchema['blocks']; layout: PageSchema['layout']; schemaVersion: 2 }) => void;
  modelCode?: string;
  modelFields?: Array<{ code: string; name: string; type: string }>;
}

export const AiPageGenerateDialog: React.FC<AiPageGenerateDialogProps> = ({
  open,
  onClose,
  onGenerated,
  modelCode,
  modelFields,
}) => {
  const [description, setDescription] = useState('');
  const [generating, setGenerating] = useState(false);
  const [preview, setPreview] = useState('');
  const [error, setError] = useState<string | null>(null);
  const sessionIdRef = useRef(`ai-page-gen-${Date.now()}`);

  if (!open) return null;

  const handleGenerate = async () => {
    if (!description.trim()) {
      setError('Please describe the page you want to create');
      return;
    }

    setGenerating(true);
    setPreview('');
    setError(null);

    const systemPrompt = buildPageGenerationPrompt(modelFields);

    try {
      await auraBotApi.chatStream(
        {
          sessionId: sessionIdRef.current,
          message: description.trim(),
          history: [{ role: 'system', content: systemPrompt }],
        },
        {
          onChunk: (chunk) => {
            setPreview((prev) => prev + chunk);
          },
          onDone: (fullContent) => {
            try {
              const dsl = parsePageDslResponse(fullContent);
              setGenerating(false);
              onGenerated(dsl);
              onClose();
            } catch (parseErr) {
              setError(`Failed to parse AI response: ${parseErr instanceof Error ? parseErr.message : String(parseErr)}`);
              setGenerating(false);
            }
          },
          onError: (errMsg) => {
            setError(errMsg || 'AI generation failed');
            setGenerating(false);
          },
        },
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : 'AI generation failed');
      setGenerating(false);
    }
  };

  const handleClose = () => {
    if (!generating) {
      onClose();
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={handleClose}>
      <div
        className="flex h-[560px] w-[640px] flex-col rounded-lg bg-white shadow-xl"
        onClick={(e) => e.stopPropagation()}
        data-testid="ai-page-generate-dialog"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <div className="flex items-center gap-2">
            <span className="text-lg">✨</span>
            <h2 className="text-lg font-semibold text-gray-900">AI Page Generator</h2>
          </div>
          <button
            onClick={handleClose}
            className="text-gray-400 hover:text-gray-600"
            disabled={generating}
          >
            &times;
          </button>
        </div>

        {/* Body */}
        <div className="flex flex-1 flex-col gap-4 overflow-y-auto p-6">
          {/* Model context indicator */}
          {modelCode && (
            <div className="flex items-center gap-2 rounded-md bg-blue-50 px-3 py-2 text-sm text-blue-700">
              <span>Model:</span>
              <span className="font-mono font-medium">{modelCode}</span>
              {modelFields && (
                <span className="text-blue-500">({modelFields.length} fields)</span>
              )}
            </div>
          )}

          {/* Description input */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Describe the page you want
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={
                modelCode
                  ? `e.g. "List all ${modelCode} records with status filter, show key fields in a table, add approve/reject buttons"`
                  : 'e.g. "A dashboard with 3 stat cards showing total orders, revenue, and avg order value, plus a bar chart of monthly revenue"'
              }
              rows={4}
              className="w-full resize-none rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-purple-500 focus:outline-none focus:ring-1 focus:ring-purple-500"
              data-testid="ai-page-description"
              disabled={generating}
              autoFocus
            />
          </div>

          {/* Preview */}
          {(preview || generating) && (
            <div className="flex-1 overflow-auto rounded-md border border-gray-200 bg-gray-50 p-3">
              <div className="mb-1 text-[10px] font-medium uppercase tracking-wider text-gray-400">
                {generating ? 'Generating...' : 'Generated DSL'}
              </div>
              <pre className="whitespace-pre-wrap font-mono text-xs text-gray-600">
                {preview || (generating ? '...' : '')}
              </pre>
            </div>
          )}

          {/* Error */}
          {error && (
            <div className="rounded-md bg-red-50 p-3 text-sm text-red-600" data-testid="ai-page-error">
              {error}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
          <button
            onClick={handleClose}
            className="rounded-md border border-gray-300 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
            disabled={generating}
          >
            Cancel
          </button>
          <button
            onClick={handleGenerate}
            disabled={generating || !description.trim()}
            className="rounded-md bg-gradient-to-r from-purple-600 to-indigo-600 px-4 py-2 text-sm text-white hover:from-purple-700 hover:to-indigo-700 disabled:opacity-50"
            data-testid="ai-page-generate-btn"
          >
            {generating ? 'Generating...' : '✨ Generate Page'}
          </button>
        </div>
      </div>
    </div>
  );
};
