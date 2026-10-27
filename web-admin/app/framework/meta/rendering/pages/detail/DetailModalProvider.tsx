import {
  createContext,
  lazy,
  Suspense,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { useFetcher, useLocation, useNavigate } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { useModalFocusTrap } from '~/hooks/useModalFocusTrap';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import {
  cancelCurrentFormDialog,
  registerFormDialogScope,
} from '~/framework/meta/runtime/actions/formDialogLifecycle';
import { DetailPresentationContext } from './DetailPresentationContext';
import { DetailSourceRefreshContext } from './DetailSourceRefreshContext';
import {
  captureDetailSourceFocus,
  restoreDetailSourceFocus,
  type DetailSourceFocus,
} from './detailModalSourceFocus';
import {
  reloadDetailModalSource,
  resolveDetailModalTarget,
  type DetailModalTarget,
} from './detailModalTarget';

const DynamicPageRenderer = lazy(() => import('../DynamicPageRenderer'));
export const DETAIL_MODAL_QUERY = 'detailModal';
type SourceRefresh = () => void | Promise<void>;
type OpenDetailModal = (target: DetailModalTarget, onClose?: SourceRefresh) => void;
const DetailModalContext = createContext<OpenDetailModal | null>(null);
export function useDetailModal() {
  return useContext(DetailModalContext);
}
interface SourceEntry {
  value: string;
  source: string;
  scope: string;
  width: number;
  marker: string;
  pushed: boolean;
  state: unknown;
  onClose?: SourceRefresh;
  focus: DetailSourceFocus | null;
}

export function DetailModalProvider({ children }: { children: ReactNode }) {
  // Portals are mounted only after hydration; restored URLs also render on the server.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  const location = useLocation();
  const navigate = useNavigate();
  const { user } = useAuth();
  const scope = `${user?.pid ?? user?.id ?? ''}:${user?.tenantId ?? ''}`;
  const params = new URLSearchParams(location.search);
  const value = params.get(DETAIL_MODAL_QUERY);
  params.delete(DETAIL_MODAL_QUERY);
  const cleanSearch = params.toString();
  const source = `${location.pathname}${cleanSearch ? `?${cleanSearch}` : ''}${location.hash}`;
  const entry = useRef<SourceEntry | null>(null);
  const previousModal = useRef<{ scope: string; source: string } | null>(null);
  const sequence = useRef(0);
  const restoreSequence = useRef(0);
  const currentLocation = useRef({ scope, source, value });
  currentLocation.current = { scope, source, value };
  const [sourceRefresh, setSourceRefresh] = useState<{
    scope: string;
    source: string;
    version: number;
  } | null>(null);
  const sourceRefreshVersion =
    sourceRefresh && sourceRefresh.scope === scope && sourceRefresh.source === source
      ? sourceRefresh.version
      : 0;
  const open = useCallback<OpenDetailModal>(
    (target, onClose) => {
      const targetValue = new URL(target.path, 'http://detail-modal.invalid').pathname;
      // Revalidate the caller as well as the URL-restoration path.
      resolveDetailModalTarget(targetValue, target.width);
      ++restoreSequence.current;
      const previous = entry.current;
      const replacing = Boolean(value);
      const sameSource = previous?.scope === scope && previous.source === source;
      const marker = sameSource ? previous.marker : `${scope}:${++sequence.current}`;
      const sourceState = replacing && sameSource ? previous.state : location.state;
      entry.current = {
        value: targetValue,
        source,
        scope,
        width: target.width,
        marker,
        pushed: replacing ? Boolean(sameSource && previous.pushed) : true,
        state: sourceState,
        onClose: sameSource ? previous.onClose : replacing ? undefined : onClose,
        focus: sameSource ? previous.focus : replacing ? null : captureDetailSourceFocus(),
      };
      const query = new URLSearchParams(location.search);
      query.set(DETAIL_MODAL_QUERY, targetValue);
      const state =
        sourceState && typeof sourceState === 'object' && !Array.isArray(sourceState)
          ? sourceState
          : {};
      navigate(
        { pathname: location.pathname, search: `?${query}`, hash: location.hash },
        {
          replace: replacing,
          preventScrollReset: true,
          state: { ...state, __aurabootDetailModal: marker },
        },
      );
    },
    [
      location.pathname,
      location.search,
      location.hash,
      location.state,
      navigate,
      scope,
      source,
      value,
    ],
  );
  useEffect(() => {
    const previous = entry.current;
    const restored = previousModal.current;
    previousModal.current = value ? { scope, source } : null;
    if (!value && !previous && restored?.scope === scope && restored.source === source) {
      // Browser Back from a restored modal also needs a genuine source read.
      reloadDetailModalSource(source);
      return;
    }
    if (
      !previous ||
      (previous.value === value && previous.scope === scope && previous.source === source)
    )
      return;
    entry.current = null;
    if (!value && previous.scope === scope && previous.source === source) {
      if (!previous.onClose) {
        reloadDetailModalSource(source);
        return;
      }
      const ticket = ++restoreSequence.current;
      let cancelled = false,
        interacted = false,
        frame: number | undefined;
      // Body focus from unmount and an older trap's automatic restore are not user intent.
      const interaction = () => {
        interacted = true;
      };
      document.addEventListener('pointerdown', interaction, true);
      document.addEventListener('keydown', interaction, true);
      const removeListeners = () => {
        document.removeEventListener('pointerdown', interaction, true);
        document.removeEventListener('keydown', interaction, true);
      };
      void (async () => {
        let failed = false;
        try {
          await previous.onClose!();
        } catch {
          failed = true;
        }
        if (cancelled) return;
        const refreshedOwner = currentLocation.current;
        if (
          !failed &&
          restoreSequence.current === ticket &&
          refreshedOwner.scope === previous.scope &&
          refreshedOwner.source === previous.source &&
          !refreshedOwner.value
        ) {
          // Only completed source refreshes invalidate the source's independent chart reads.
          // Deliberate focus changes suppress focus restoration, not data freshness.
          setSourceRefresh((current) => ({
            scope: previous.scope,
            source: previous.source,
            version:
              current && current.scope === previous.scope && current.source === previous.source
                ? current.version + 1
                : 1,
          }));
        }
        frame = window.requestAnimationFrame(() => {
          removeListeners();
          const current = currentLocation.current;
          if (
            cancelled ||
            interacted ||
            restoreSequence.current !== ticket ||
            current.scope !== previous.scope ||
            current.source !== previous.source ||
            current.value
          )
            return;
          const active = document.activeElement;
          // Preserve a deliberate new control even when focus was moved programmatically.
          if (
            active instanceof HTMLElement &&
            active !== document.body &&
            active !== document.documentElement &&
            active !== previous.focus?.opener &&
            active.isConnected
          )
            return;
          restoreDetailSourceFocus(previous.focus, failed);
        });
      })();
      return () => {
        cancelled = true;
        removeListeners();
        if (frame !== undefined) window.cancelAnimationFrame(frame);
      };
    }
  }, [value, scope, source]);
  const close = useCallback(() => {
    const current = entry.current;
    if (
      current?.scope === scope &&
      current.source === source &&
      current.pushed &&
      location.state?.__aurabootDetailModal === current.marker
    ) {
      navigate(-1);
      return;
    }
    // Restored/deep-linked modal has no live source manager or opener callback.
    // Perform a real source load instead of claiming router revalidation fetched DMS.
    reloadDetailModalSource(source);
  }, [scope, source, location.state, navigate]);
  let target: DetailModalTarget | null = null;
  if (value) {
    try {
      const width =
        entry.current?.value === value && entry.current.scope === scope ? entry.current.width : 900;
      target = resolveDetailModalTarget(value, width);
    } catch {
      /* The invalid URL is rendered as an error, never navigated or fetched. */
    }
  }
  return (
    <DetailModalContext.Provider value={open}>
      <DetailSourceRefreshContext.Provider value={sourceRefreshVersion}>
        {children}
      </DetailSourceRefreshContext.Provider>
      {mounted && value ? (
        <DetailModal key={`${scope}:${source}:${value}`} target={target} onClose={close} />
      ) : null}
    </DetailModalContext.Provider>
  );
}

interface DetailLoaderData {
  tableName: string;
  recordPid: string;
  accessError: string | null;
  loadError?: string;
  httpStatus?: number;
}
function DetailModal({
  target,
  onClose,
}: {
  target: DetailModalTarget | null;
  onClose: () => void;
}) {
  const { t, locale } = useI18n();
  const closeLabel = t('common.close', undefined, locale.startsWith('zh') ? '关闭' : 'Close');
  const genericTitle = t(
    'common.details',
    undefined,
    locale.startsWith('zh') ? '记录详情' : 'Record details',
  );
  const [recordTitle, setTitle] = useState<string | null>(null);
  const title = recordTitle || genericTitle;
  const ref = useRef<HTMLDivElement>(null);
  const [footerTarget, setFooterTarget] = useState<HTMLDivElement | null>(null);
  useModalFocusTrap(true, ref, onClose, undefined, false);
  useLayoutEffect(() => {
    const controller = new AbortController();
    const unregister = registerFormDialogScope(controller.signal, 1);
    return () => {
      controller.abort();
      unregister();
      cancelCurrentFormDialog();
    };
  }, []);
  const { load, state, data } = useFetcher<DetailLoaderData>();
  const path = target?.path;
  useEffect(() => {
    if (path) void load(path);
  }, [load, path]);
  const matches =
    target && data?.tableName === target.tableName && data?.recordPid === target.recordPid;
  const denied = matches && Boolean(data?.accessError);
  const failed = matches && Boolean(data?.loadError);
  return createPortal(
    <div
      className="aura-dialog-presentation fixed inset-0 z-[55] flex items-center justify-center"
      data-testid="detail-modal"
    >
      <div
        className="aura-dialog-presentation__backdrop absolute inset-0"
        aria-hidden="true"
        data-testid="detail-modal-backdrop"
      />
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby="detail-modal-title"
        tabIndex={-1}
        className="aura-dialog-presentation__panel border-border bg-panel relative flex min-h-0 flex-col overflow-hidden border"
        style={{ '--dialog-width': `${target?.width ?? 900}px` } as React.CSSProperties}
      >
        <div className="aura-dialog-presentation__header border-border flex shrink-0 items-center justify-between gap-4 border-b">
          <h2
            id="detail-modal-title"
            className="text-text min-w-0 text-lg font-semibold break-words"
          >
            {title}
          </h2>
          <button
            type="button"
            aria-label={closeLabel}
            data-testid="detail-modal-close"
            onClick={onClose}
            className="rounded-control text-text-3 hover:bg-hover px-2 text-xl focus-visible:shadow-[var(--shadow-focus)]"
          >
            ×
          </button>
        </div>
        <div
          className="aura-dialog-presentation__body min-h-0 flex-1 overflow-y-auto"
          data-testid="detail-modal-body"
          data-record-pid={target?.recordPid}
        >
          {!target ? (
            <p role="alert" className="p-6" data-status="400">
              {locale.startsWith('zh') ? '无效的详情入口。' : 'Invalid detail target.'}
            </p>
          ) : state !== 'idle' || !matches ? (
            <LoadingSpinner />
          ) : denied || failed ? (
            <div className="space-y-4 p-6" role="alert" data-status={data?.httpStatus}>
              <p>
                {denied
                  ? locale.startsWith('zh')
                    ? '无权限访问此页面，请联系管理员。'
                    : 'Access denied. Contact your administrator.'
                  : locale.startsWith('zh')
                    ? '详情载入失败，请重试。'
                    : 'Unable to load the detail. Try again.'}
              </p>
              {!denied && (
                <button
                  type="button"
                  onClick={() => {
                    void load(target.path);
                  }}
                  className="rounded-control border-border border px-3 py-2"
                >
                  {t('common.retry', undefined, locale.startsWith('zh') ? '重试' : 'Retry')}
                </button>
              )}
              <button
                type="button"
                onClick={onClose}
                className="rounded-control border-border border px-3 py-2"
              >
                {closeLabel}
              </button>
            </div>
          ) : (
            <DetailPresentationContext.Provider
              value={{ mode: 'modal', onClose, footerTarget, setTitle }}
            >
              <Suspense fallback={<LoadingSpinner />}>
                <DynamicPageRenderer
                  key={`${target.tableName}:${target.recordPid}`}
                  tableName={target.tableName}
                  pageType="detail"
                  recordPid={target.recordPid}
                />
              </Suspense>
            </DetailPresentationContext.Provider>
          )}
        </div>
        <div ref={setFooterTarget} className="shrink-0" />
      </div>
    </div>,
    document.body,
  );
}
