/** Anonymous, token-scoped view configuration; business records are not part of this HTTP response. */
import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router';
import { useRootLoaderData } from '~/root-data';
import { COMMUNITY_BRANDING } from '~/config/branding';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import { ResultHelper } from '~/utils/type';
import { loadPublicSharedView, type PublicSharedView } from '~/shared/services/savedViewService';
import TEXT from './share.i18n.json';

type Failure = 'unavailable' | 'expired' | 'revoked' | 'network' | 'invalidPayload';
const VIEW_KINDS = new Set([
  'table',
  'kanban',
  'calendar',
  'gallery',
  'gantt',
  'tree',
  'timeline',
  'form',
]);

export default function SharedViewPage() {
  const branding = useRootLoaderData()?.branding ?? COMMUNITY_BRANDING;
  const { locale } = useI18n();
  const text = (key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale);
  const { token } = useParams<{ token: string }>();
  const [searchParams] = useSearchParams();
  const passwordParam = searchParams.get('password');
  const pending = useRef<AbortController | null>(null);
  const [data, setData] = useState<PublicSharedView | null>(null);
  const [error, setError] = useState<Failure | null>(null);
  const [loading, setLoading] = useState(true);
  const [passwordRequired, setPasswordRequired] = useState(false);
  const [passwordInvalid, setPasswordInvalid] = useState(false);
  const [password, setPassword] = useState('');

  const fetchSharedView = useCallback(
    async (pwd?: string) => {
      pending.current?.abort();
      const controller = new AbortController();
      pending.current = controller;
      setLoading(true);
      setError(null);
      setData(null);
      setPasswordInvalid(false);
      try {
        if (!token) {
          setError('unavailable');
          return;
        }
        const result = await loadPublicSharedView(token, pwd, controller.signal);
        if (controller.signal.aborted) return;
        if (!ResultHelper.isSuccess(result)) {
          // Validation context is preserved separately from the localized envelope message.
          const detail = result.context?.error;
          if (result.httpStatus === 422 && detail === 'Invalid password') {
            setPasswordRequired(true);
            setPasswordInvalid(Boolean(pwd));
          } else if (detail === 'Share link has expired') setError('expired');
          else if (detail === 'Share link has been revoked') setError('revoked');
          else setError('unavailable');
          return;
        }
        if (!result.data) {
          setError('invalidPayload');
          return;
        }
        setData(result.data);
        setPasswordRequired(false);
      } catch {
        if (!controller.signal.aborted) setError('network');
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    },
    [token],
  );

  useEffect(() => {
    void fetchSharedView(passwordParam || undefined);
    return () => pending.current?.abort();
  }, [fetchSharedView, passwordParam]);

  if (loading)
    return (
      <div
        role="status"
        aria-label={text('loading')}
        className="flex min-h-screen items-center justify-center bg-gray-50"
      >
        <span className="text-sm text-gray-600">{text('loading')}</span>
      </div>
    );
  if (error)
    return (
      <main className="flex min-h-screen items-center justify-center bg-gray-50 p-6">
        <div
          role="alert"
          className="max-w-md rounded-xl border border-gray-200 bg-white p-8 text-center shadow-sm"
        >
          <h1 className="text-lg font-semibold text-gray-900">{text(error)}</h1>
          <button
            type="button"
            onClick={() => void fetchSharedView(password || passwordParam || undefined)}
            className="mt-5 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white"
          >
            {text('retry')}
          </button>
        </div>
      </main>
    );
  if (passwordRequired)
    return (
      <main className="flex min-h-screen items-center justify-center bg-gray-50 p-6">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void fetchSharedView(password);
          }}
          className="w-full max-w-sm space-y-4 rounded-xl border border-gray-200 bg-white p-6 shadow-sm"
        >
          <h1 className="text-lg font-semibold text-gray-900">{text('passwordTitle')}</h1>
          <p className="text-sm text-gray-600">{text('passwordHelp')}</p>
          <label className="block text-sm font-medium text-gray-700">
            {text('passwordLabel')}
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder={text('passwordPlaceholder')}
              aria-invalid={passwordInvalid}
              aria-describedby={passwordInvalid ? 'share-password-error' : undefined}
              className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
            />
          </label>
          {passwordInvalid && (
            <p id="share-password-error" role="alert" className="text-sm text-red-600">
              {text('passwordInvalid')}
            </p>
          )}
          <button
            type="submit"
            className="w-full rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white"
          >
            {text('open')}
          </button>
        </form>
      </main>
    );
  if (!data) return null;
  const kind = data.viewType === 'list' ? 'table' : data.viewType;
  const label = kind && VIEW_KINDS.has(kind) ? text(kind as keyof typeof TEXT) : text('view');
  return (
    <main className="min-h-screen bg-gray-50 p-6" data-testid="public-shared-view">
      <div className="mx-auto max-w-4xl space-y-6">
        <header className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 className="text-2xl font-semibold break-words text-gray-900">{data.name}</h1>
            <p className="mt-1 text-sm text-gray-600">{label}</p>
          </div>
          <span className="rounded-full bg-blue-100 px-3 py-1 text-sm font-medium text-blue-700">
            {text('readOnly')}
          </span>
        </header>
        <section className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="text-lg font-semibold text-gray-900">{text('configuration')}</h2>
          <p className="mt-2 text-sm leading-6 text-gray-600">{text('configurationHelp')}</p>
          <dl className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
            {(['columns', 'filters', 'sorts'] as const).map((key) => (
              <div key={key} className="rounded-lg border border-gray-100 bg-gray-50 p-4">
                <dt className="text-sm text-gray-600">{text(key)}</dt>
                <dd className="mt-1 text-2xl font-semibold text-gray-900">
                  {Array.isArray(data.viewConfig[key]) ? data.viewConfig[key].length : 0}
                </dd>
              </div>
            ))}
          </dl>
        </section>
        <footer className="text-center text-xs text-gray-500">{branding.poweredByText}</footer>
      </div>
    </main>
  );
}
