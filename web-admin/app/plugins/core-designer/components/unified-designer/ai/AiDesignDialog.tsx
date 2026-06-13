/**
 * AiDesignDialog — natural-language → unified-designer V3 blocks.
 *
 * Mirrors the Page Designer's AiPageGenerateDialog but emits V3 DslBlockV3 blocks
 * for the unified designer. Calls the dedicated tools-off completion endpoint
 * (POST /api/agent/nl-modeling/generate-page) with a system prompt built by the
 * workbench from the live page context, parses the response, and hands the parsed
 * design back via onApply for the workbench to merge into the document.
 *
 * @since 4.2.0
 */

import React, { useState } from 'react';
import { parseDesignCopilotResponse, type ParsedDesign } from './designCopilot';

export interface AiDesignDialogProps {
  open: boolean;
  onClose: () => void;
  /** Full system prompt (built by the workbench from live kind/blocks/fields/domain). */
  systemPrompt: string;
  /** Existing block ids in the document, so generated ids never collide. */
  existingIds?: Set<string>;
  /** Apply the parsed design to the document. */
  onApply: (parsed: ParsedDesign) => void;
}

export const AiDesignDialog: React.FC<AiDesignDialogProps> = ({
  open,
  onClose,
  systemPrompt,
  existingIds,
  onApply,
}) => {
  const [description, setDescription] = useState('');
  const [generating, setGenerating] = useState(false);
  const [preview, setPreview] = useState('');
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const handleGenerate = async () => {
    if (!description.trim()) {
      setError('请先描述你想生成的内容');
      return;
    }
    setGenerating(true);
    setPreview('');
    setError(null);
    try {
      const resp = await fetch('/api/agent/nl-modeling/generate-page', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ systemPrompt, message: description.trim() }),
      });
      const data = await resp.json().catch(() => ({}));
      if (!resp.ok || !data.content) {
        setError(data.error || `AI 生成失败 (${resp.status})`);
        setGenerating(false);
        return;
      }
      setPreview(data.content);
      let parsed: ParsedDesign;
      try {
        parsed = parseDesignCopilotResponse(data.content, { existingIds });
      } catch (parseErr) {
        setError(`AI 响应解析失败：${parseErr instanceof Error ? parseErr.message : String(parseErr)}`);
        setGenerating(false);
        return;
      }
      setGenerating(false);
      onApply(parsed);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'AI 生成失败');
      setGenerating(false);
    }
  };

  const handleClose = () => {
    if (!generating) onClose();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      onClick={handleClose}
    >
      <div
        className="flex h-[560px] w-[640px] flex-col rounded-lg bg-white shadow-xl"
        onClick={(e) => e.stopPropagation()}
        data-testid="ai-design-dialog"
      >
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <div className="flex items-center gap-2">
            <span className="text-lg">✨</span>
            <h2 className="text-lg font-semibold text-gray-900">AI 设计副驾</h2>
          </div>
          <button
            onClick={handleClose}
            className="text-gray-400 hover:text-gray-600"
            disabled={generating}
            aria-label="close"
          >
            &times;
          </button>
        </div>

        <div className="flex flex-1 flex-col gap-4 overflow-y-auto p-6">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              描述你想生成或修改的内容
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="例如：“加一个客户信息分组，包含姓名、电话、地址三个字段，再加一个提交按钮”"
              rows={4}
              className="w-full resize-none rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-purple-500 focus:outline-none focus:ring-1 focus:ring-purple-500"
              data-testid="ai-design-description"
              disabled={generating}
              autoFocus
            />
          </div>

          {(preview || generating) && (
            <div className="flex-1 overflow-auto rounded-md border border-gray-200 bg-gray-50 p-3">
              <div className="mb-1 text-[10px] font-medium uppercase tracking-wider text-gray-400">
                {generating ? '生成中...' : '生成结果'}
              </div>
              <pre className="whitespace-pre-wrap font-mono text-xs text-gray-600">
                {preview || (generating ? '...' : '')}
              </pre>
            </div>
          )}

          {error && (
            <div className="rounded-md bg-red-50 p-3 text-sm text-red-600" data-testid="ai-design-error">
              {error}
            </div>
          )}
        </div>

        <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
          <button
            onClick={handleClose}
            className="rounded-md border border-gray-300 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
            disabled={generating}
          >
            取消
          </button>
          <button
            onClick={handleGenerate}
            disabled={generating || !description.trim()}
            className="rounded-md bg-gradient-to-r from-purple-600 to-indigo-600 px-4 py-2 text-sm text-white hover:from-purple-700 hover:to-indigo-700 disabled:opacity-50"
            data-testid="ai-design-generate-btn"
          >
            {generating ? '生成中...' : '✨ 生成'}
          </button>
        </div>
      </div>
    </div>
  );
};
