import {
  createContext,
  useCallback,
  useContext,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useLocation } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { FilePreviewModal } from '~/framework/smart/components/common/FilePreviewModal';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import {
  downloadWithAuth,
  FileDownloadHttpError,
  readProtectedFile,
} from '~/framework/meta/utils/commandDownload';
import copy from './ProtectedFilePreviewProvider.i18n.json';

interface PreviewRequest {
  url: string;
  contentType: 'application/pdf';
  signal?: AbortSignal;
  returnFocusTo?: HTMLElement | null;
}
type OpenPreview = (request: PreviewRequest) => Promise<void>;
type Lifetime = {
  scope: string;
  controller: AbortController;
  objectUrl?: string;
  unlink?: () => void;
};
type Preview = {
  scope: string;
  objectUrl: string;
  url: string;
  name: string;
  lifetime: Lifetime;
  returnFocusTo?: HTMLElement | null;
};
const Context = createContext<OpenPreview | null>(null);
export function useProtectedFilePreview() {
  return useContext(Context);
}
function release(lifetime: Lifetime | null) {
  if (!lifetime) return;
  lifetime.unlink?.();
  lifetime.controller.abort();
  if (lifetime.objectUrl) {
    URL.revokeObjectURL(lifetime.objectUrl);
    lifetime.objectUrl = undefined;
  }
}

/** A preview is transient UI state, bound to its initiating route and authenticated identity. */
export function ProtectedFilePreviewProvider({ children }: { children: ReactNode }) {
  const location = useLocation(),
    { user } = useAuth(),
    { locale } = useI18n();
  const scope = `${location.key}:${user?.pid ?? user?.id ?? ''}:${user?.tenantId ?? ''}`;
  const scopeRef = useRef(scope);
  scopeRef.current = scope;
  const active = useRef<Lifetime | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState('');
  const text = useCallback(
    (key: keyof typeof copy) => getLocalizedText(copy[key], locale),
    [locale],
  );
  const close = useCallback(() => {
    release(active.current);
    active.current = null;
    setPreview(null);
    setDownloading(false);
    setDownloadError('');
  }, []);
  useLayoutEffect(() => {
    setPreview(null);
    setDownloading(false);
    setDownloadError('');
    return () => {
      release(active.current);
      active.current = null;
    };
  }, [scope]);

  const open = useCallback<OpenPreview>(
    async (request) => {
      if (scopeRef.current !== scope) return;
      close();
      if (
        request.contentType !== 'application/pdf' ||
        !/^\/api\/file\/download\/[0-9A-HJKMNP-TV-Z]{26}$/.test(request.url)
      )
        throw new Error(text('invalid'));
      if (request.signal?.aborted) return;
      const lifetime: Lifetime = { scope, controller: new AbortController() };
      active.current = lifetime;
      const cancelled = () => {
        if (active.current === lifetime) close();
        else release(lifetime);
      };
      request.signal?.addEventListener('abort', cancelled, { once: true });
      lifetime.unlink = () => request.signal?.removeEventListener('abort', cancelled);
      try {
        const file = await readProtectedFile(request.url, {
          expectedContentTypes: ['application/pdf'],
          requireNamedDisposition: true,
          requireNonEmpty: true,
          signal: lifetime.controller.signal,
          resolveFailure: async (response) =>
            text(response.status === 401 || response.status === 403 ? 'denied' : 'unavailable'),
        });
        if (
          lifetime.controller.signal.aborted ||
          active.current !== lifetime ||
          scopeRef.current !== scope
        )
          return;
        if (file.blob.size > 33_554_432 || !file.fileName) throw new Error(text('invalid'));
        const magic = new Uint8Array(await file.blob.slice(0, 5).arrayBuffer());
        if (
          magic.length !== 5 ||
          magic.some((value, index) => value !== [37, 80, 68, 70, 45][index])
        )
          throw new Error(text('invalid'));
        if (
          lifetime.controller.signal.aborted ||
          active.current !== lifetime ||
          scopeRef.current !== scope
        )
          return;
        const objectUrl = URL.createObjectURL(file.blob);
        lifetime.objectUrl = objectUrl;
        setPreview({
          scope,
          objectUrl,
          url: request.url,
          name: file.fileName,
          lifetime,
          returnFocusTo: request.returnFocusTo,
        });
      } catch (failure) {
        const cancelledRequest =
          lifetime.controller.signal.aborted ||
          active.current !== lifetime ||
          scopeRef.current !== scope;
        release(lifetime);
        if (active.current === lifetime) active.current = null;
        if (!cancelledRequest)
          throw new Error(
            failure instanceof FileDownloadHttpError ? failure.message : text('invalid'),
            { cause: failure },
          );
      }
    },
    [close, scope, text],
  );

  const download = useCallback(async () => {
    if (
      !preview ||
      preview.scope !== scopeRef.current ||
      preview.lifetime !== active.current ||
      downloading
    )
      return;
    setDownloading(true);
    setDownloadError('');
    try {
      // Reauthorize the explicit download; the preview's Blob is never its download source.
      await downloadWithAuth(preview.url, {
        expectedContentTypes: ['application/pdf'],
        requireNamedDisposition: true,
        requireNonEmpty: true,
        signal: preview.lifetime.controller.signal,
        resolveFailure: async (response) =>
          text(response.status === 401 || response.status === 403 ? 'denied' : 'unavailable'),
      });
    } catch (failure) {
      if (preview.lifetime === active.current && !preview.lifetime.controller.signal.aborted)
        setDownloadError(
          failure instanceof FileDownloadHttpError ? failure.message : text('unavailable'),
        );
    } finally {
      if (preview.lifetime === active.current) setDownloading(false);
    }
  }, [preview, downloading, text]);

  return (
    <Context.Provider value={open}>
      {children}
      {preview?.scope === scope && (
        <FilePreviewModal
          open
          onClose={close}
          fileUrl={preview.objectUrl}
          fileName={preview.name}
          fileType="application/pdf"
          description={text('description')}
          closeLabel={text('close')}
          onCloseAutoFocus={(event) => {
            const target = preview.returnFocusTo;
            if (
              preview.scope === scopeRef.current &&
              target?.isConnected &&
              !target.matches(':disabled')
            ) {
              event.preventDefault();
              target.focus();
            }
          }}
          downloadAction={{
            label: text('download'),
            busy: downloading,
            error: downloadError,
            onClick: () => {
              void download();
            },
          }}
        />
      )}
    </Context.Provider>
  );
}
