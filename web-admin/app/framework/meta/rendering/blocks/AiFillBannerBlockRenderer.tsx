/**
 * AiFillBannerBlockRenderer — P1' vertical-slice block.
 *
 * Renders a banner with a "智能填写" button above the form. Clicking opens
 * a dialog with a single NL textarea; on submit it POSTs to the backend
 * /api/wd-leave-request/ai-fill endpoint and applies the returned field
 * map via DslFormFillContext.
 *
 * Block schema:
 *   { id, blockType: "ai-fill-banner",
 *     endpoint: string (defaults to /api/wd-leave-request/ai-fill),
 *     placeholder?: LocalizedText, examples?: string[] }
 *
 * P2' will replace this with a generic schema-driven AI fill widget that
 * derives the endpoint + field schema from the surrounding form's modelCode.
 * Do not extend this component — replace it.
 */
import React, { useCallback, useState } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useDslFormFill } from '~/framework/meta/rendering/DslFormFillContext';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface AiFillBannerBlockProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

interface AiFillResponse {
  turnId: string;
  fields: Record<string, unknown>;
  annotationId: number | null;
  totalTokens: number;
  totalDollars: number;
  errorKey: string | null;
}

const DEFAULT_ENDPOINT = '/api/wd-leave-request/ai-fill';

export const AiFillBannerBlockRenderer: React.FC<AiFillBannerBlockProps> = ({ block, runtime }) => {
  const { applyFields } = useDslFormFill();
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  const [open, setOpen] = useState(false);
  const [nlInput, setNlInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const endpoint = (block as any).endpoint || DEFAULT_ENDPOINT;
  const placeholder = getLocalizedText((block as any).placeholder, locale)
    || t('ai.fill.placeholder')
    || (locale === 'zh-CN'
        ? '示例:下周三家里有事请假 2 天'
        : 'e.g. take 2 days off starting next Wednesday for personal reasons');
  const examples: string[] = (block as any).examples || [];

  const onSubmit = useCallback(async () => {
    if (!nlInput.trim()) {
      setError(t('ai.fill.nl_input_required') || 'Please enter a description.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const today = new Date().toISOString().slice(0, 10);
      const result = await post<AiFillResponse>(endpoint, {
        nlInput,
        currentDate: today,
      });
      if (!ResultHelper.isSuccess(result)) {
        setError(result.message || result.desc || `Request failed (${result.code})`);
        return;
      }
      const data = result.data;
      if (data?.errorKey) {
        setError(t(data.errorKey) || data.errorKey);
        return;
      }
      if (data?.fields && Object.keys(data.fields).length > 0) {
        applyFields(data.fields);
        setOpen(false);
        setNlInput('');
      } else {
        setError(t('ai.fill.no_fields_extracted')
          || 'AI could not extract any fields from your input.');
      }
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      setError(message);
    } finally {
      setLoading(false);
    }
  }, [nlInput, endpoint, applyFields, t]);

  return (
    <div data-testid="ai-fill-banner" className="mb-4 rounded-md border border-blue-200 bg-blue-50 p-4">
      <div className="flex items-center justify-between gap-4">
        <div>
          <div className="font-medium text-blue-900">
            {t('ai.fill.banner_title') || (locale === 'zh-CN' ? '智能填写' : 'AI Fill')}
          </div>
          <div className="text-sm text-blue-700">
            {t('ai.fill.banner_hint')
              || (locale === 'zh-CN'
                  ? '用自然语言描述,AI 帮你填好表单'
                  : 'Describe in natural language; AI fills the form for you')}
          </div>
        </div>
        <button
          type="button"
          data-testid="ai-fill-trigger"
          onClick={() => setOpen(true)}
          className="rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          {t('ai.fill.button_label') || (locale === 'zh-CN' ? '智能填写' : 'Fill with AI')}
        </button>
      </div>

      {open && (
        <div
          role="dialog"
          aria-modal="true"
          data-testid="ai-fill-dialog"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
          onClick={() => !loading && setOpen(false)}
        >
          <div
            className="w-[480px] max-w-[92vw] rounded-md bg-white p-5 shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-3 text-base font-semibold">
              {t('ai.fill.dialog_title') || (locale === 'zh-CN' ? '智能填写' : 'AI Fill')}
            </div>
            <textarea
              data-testid="ai-fill-input"
              value={nlInput}
              onChange={(e) => setNlInput(e.target.value)}
              placeholder={placeholder}
              rows={4}
              className="mb-3 w-full resize-y rounded border border-gray-300 p-2 text-sm focus:border-blue-500 focus:outline-none"
              disabled={loading}
            />
            {examples.length > 0 && (
              <div className="mb-3 text-xs text-gray-500">
                {(t('ai.fill.examples_label') || (locale === 'zh-CN' ? '示例:' : 'Examples:'))}{' '}
                {examples.map((ex, i) => (
                  <button
                    key={i}
                    type="button"
                    onClick={() => setNlInput(ex)}
                    className="ml-1 inline-block rounded bg-gray-100 px-2 py-0.5 hover:bg-gray-200"
                    disabled={loading}
                  >
                    {ex}
                  </button>
                ))}
              </div>
            )}
            {error && (
              <div data-testid="ai-fill-error" className="mb-3 rounded bg-red-50 p-2 text-sm text-red-700">
                {error}
              </div>
            )}
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setOpen(false)}
                disabled={loading}
                className="rounded px-3 py-1.5 text-sm hover:bg-gray-100"
              >
                {t('common.cancel') || (locale === 'zh-CN' ? '取消' : 'Cancel')}
              </button>
              <button
                type="button"
                data-testid="ai-fill-confirm"
                onClick={onSubmit}
                disabled={loading || !nlInput.trim()}
                className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:bg-blue-300"
              >
                {loading
                  ? (t('ai.fill.parsing') || (locale === 'zh-CN' ? '解析中…' : 'Parsing…'))
                  : (t('ai.fill.apply') || (locale === 'zh-CN' ? '应用' : 'Apply'))}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default AiFillBannerBlockRenderer;
