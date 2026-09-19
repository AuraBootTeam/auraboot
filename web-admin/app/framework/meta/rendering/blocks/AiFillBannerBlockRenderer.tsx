/** Generic text extraction via the conversation runtime; explicit endpoints remain supported. */
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



export const AiFillBannerBlockRenderer: React.FC<AiFillBannerBlockProps> = ({ block, runtime }) => {
  const { applyFields, extractFromText, lockedFields } = useDslFormFill();
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  const [open, setOpen] = useState(false);
  const [nlInput, setNlInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const endpoint = (block as any).endpoint;
  const placeholder =
    getLocalizedText((block as any).placeholder, locale) ||
    t('ai.fill.placeholder') ||
    (locale === 'zh-CN'
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
      if (!endpoint) {
        await extractFromText(nlInput);
        setOpen(false);
        setNlInput('');
        return;
      }
      const today = new Intl.DateTimeFormat('en-CA').format(new Date());
      const result = await post<AiFillResponse>(endpoint, {
        nlInput,
        currentDate: today,
        // D5: tell the backend which fields are AI-locked so it skips them too.
        lockedFields,
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
        setError(
          t('ai.fill.no_fields_extracted') || 'AI could not extract any fields from your input.',
        );
      }
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      setError(message.startsWith('ai.fill.') ? t(message) : t('ai.fill.failed'));
    } finally {
      setLoading(false);
    }
  }, [nlInput, endpoint, applyFields, extractFromText, lockedFields, t]);

  return (
    <div
      data-testid="ai-fill-banner"
      className="rounded-control bg-accent-weak mb-4 border border-status-blue p-4"
    >
      <div className="flex items-center justify-between gap-4">
        <div>
          <div className="font-medium text-text">
            {t('ai.fill.banner_title') || (locale === 'zh-CN' ? '智能填写' : 'AI Fill')}
          </div>
          <div className="text-accent text-sm">
            {t('ai.fill.banner_hint') ||
              (locale === 'zh-CN'
                ? '用自然语言描述,AI 帮你填好表单'
                : 'Describe in natural language; AI fills the form for you')}
          </div>
        </div>
        <button
          type="button"
          data-testid="ai-fill-trigger"
          onClick={() => setOpen(true)}
          className="bg-accent hover:bg-accent-hover rounded px-4 py-2 text-sm font-medium text-white"
        >
          {t('ai.fill.button_label') || (locale === 'zh-CN' ? '智能填写' : 'Fill with AI')}
        </button>
      </div>

      {open && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={t('ai.fill.dialog_title')}
          onKeyDown={(event) => { if (event.key === 'Escape' && !loading) setOpen(false); }}
          data-testid="ai-fill-dialog"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
          onClick={() => !loading && setOpen(false)}
        >
          <div
            className="rounded-control bg-panel w-[480px] max-w-[92vw] p-5 shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-3 text-base font-semibold">
              {t('ai.fill.dialog_title') || (locale === 'zh-CN' ? '智能填写' : 'AI Fill')}
            </div>
            <textarea
              data-testid="ai-fill-input"
              aria-label={t('ai.fill.placeholder')}
              autoFocus
              value={nlInput}
              onChange={(e) => setNlInput(e.target.value)}
              placeholder={placeholder}
              rows={4}
              className="border-border-strong focus-visible:shadow-focus mb-3 w-full resize-y rounded border p-2 text-sm focus:outline-none"
              disabled={loading}
            />
            {examples.length > 0 && (
              <div className="text-text-2 mb-3 text-xs">
                {t('ai.fill.examples_label') || (locale === 'zh-CN' ? '示例:' : 'Examples:')}{' '}
                {examples.map((ex, i) => (
                  <button
                    key={i}
                    type="button"
                    onClick={() => setNlInput(ex)}
                    className="bg-hover hover:bg-hover ml-1 inline-block rounded px-2 py-0.5"
                    disabled={loading}
                  >
                    {ex}
                  </button>
                ))}
              </div>
            )}
            {error && (
              <div
                data-testid="ai-fill-error"
                className="bg-status-red-bg mb-3 rounded p-2 text-sm text-status-red"
              >
                {error}
              </div>
            )}
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setOpen(false)}
                disabled={loading}
                className="hover:bg-hover rounded px-3 py-1.5 text-sm disabled:text-text-3 disabled:cursor-not-allowed"
              >
                {t('common.cancel') || (locale === 'zh-CN' ? '取消' : 'Cancel')}
              </button>
              <button
                type="button"
                data-testid="ai-fill-confirm"
                onClick={onSubmit}
                disabled={loading || !nlInput.trim()}
                aria-busy={loading}
                className="bg-accent hover:bg-accent-hover rounded px-3 py-1.5 text-sm text-white disabled:bg-accent-weak disabled:text-text-2 disabled:cursor-not-allowed"
              >
                {loading
                  ? t('ai.fill.parsing') || (locale === 'zh-CN' ? '解析中…' : 'Parsing…')
                  : t('ai.fill.apply') || (locale === 'zh-CN' ? '应用' : 'Apply')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default AiFillBannerBlockRenderer;
