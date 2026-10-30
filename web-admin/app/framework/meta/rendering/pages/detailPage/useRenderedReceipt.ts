import { useEffect, useRef, useState, type RefObject } from 'react';
import { useAuth } from '~/contexts/AuthContext';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { applyRecordEndpointTemplate } from './detailRecordRequests';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import RECEIPT_TEXT from './RenderedReceipt.i18n.json';

export interface RenderedReceiptConfig {
  bodyField: string;
  endpoint: string;
  payloadFields: Record<string, string>;
}

/** An opt-in detail receipt runs only after the actual sanitized body has mounted visibly. */
export function useRenderedReceipt({ config, root, record, loading, failed, recordPid, onReceipt }: {
  config?: RenderedReceiptConfig;
  root: RefObject<HTMLElement | null>;
  record: Record<string, any>;
  loading: boolean;
  failed: boolean;
  recordPid: string;
  onReceipt: (record: Record<string, any>) => void;
}) {
  const { user } = useAuth();
  const { locale } = useI18n();
  const bodyUnavailable = getLocalizedText(RECEIPT_TEXT.bodyUnavailable, locale);
  const scope = `${user?.tenantId ?? ''}:${user?.pid ?? ''}`;
  const done = useRef(new Set<string>());
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const payload = config ? Object.fromEntries(Object.entries(config.payloadFields).map(([key, field]) => [key, record[field]])) : {};
  const fingerprint = JSON.stringify(payload);
  const body = config ? record[config.bodyField] : undefined;
  const callback = useRef(onReceipt); callback.current = onReceipt;
  useEffect(() => {
    setError(null);
    if (!config || loading || failed || record.pid !== recordPid || typeof body !== 'string' || !body.trim()) return;
    if (!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(config.bodyField) || !config.endpoint.startsWith('/api/')) {
      setError('Invalid rendered receipt configuration'); return;
    }
    const endpoint = applyRecordEndpointTemplate(config.endpoint, recordPid);
    const key = `${scope}:${endpoint}:${fingerprint}`;
    if (done.current.has(key)) return;
    let canceled = false, sending = false;
    const send = async () => {
      const element = root.current?.querySelector<HTMLElement>(`[data-rendered-field="${config.bodyField}"]`);
      if (canceled || sending || !element || document.visibilityState === 'hidden' || element.getClientRects().length === 0) return;
      if (!element.textContent?.trim() && !element.querySelector('img')) return;
      const images = Array.from(element.querySelectorAll('img'));
      if (images.some(image => image.complete && image.naturalWidth === 0)) {
        setError(bodyUnavailable); return;
      }
      if (images.some(image => !image.complete)) return;
      setError(null);
      sending = true;
      try {
        const result = await fetchResult<Record<string, any>>(endpoint, { method: 'post', params: JSON.parse(fingerprint) });
        if (canceled) return;
        if (!ResultHelper.isSuccess(result) || !result.data || result.data.pid !== recordPid) throw new Error(result.message || 'Receipt could not be recorded');
        done.current.add(key); callback.current(result.data);
      } catch (failure) {
        if (!canceled) setError(failure instanceof Error ? failure.message : 'Receipt could not be recorded');
      }
    };
    const frame = requestAnimationFrame(() => void send());
    const handleLoad = () => { void send(); };
    const container = root.current;
    container?.addEventListener('load', handleLoad, true);
    container?.addEventListener('error', handleLoad, true);
    document.addEventListener('visibilitychange', handleLoad);
    return () => { canceled = true; cancelAnimationFrame(frame); container?.removeEventListener('load', handleLoad, true); container?.removeEventListener('error', handleLoad, true); document.removeEventListener('visibilitychange', handleLoad); };
  }, [config, root, recordPid, record.pid, body, loading, failed, fingerprint, scope, attempt, bodyUnavailable]);
  return { error, retry: () => {
    const element = config && root.current?.querySelector<HTMLElement>(`[data-rendered-field="${config.bodyField}"]`);
    element?.querySelectorAll('img').forEach(image => {
      if (image.complete && image.naturalWidth === 0 && image.getAttribute('src')) image.src = image.currentSrc || image.src;
    });
    setAttempt(value => value + 1);
  } };
}
