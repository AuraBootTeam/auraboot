import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { navigationService } from './navigationService';

type Target = Awaited<ReturnType<typeof navigationService.target>>;
export default function EmbeddedNavigation() {
  const { entryPid } = useParams();
  const { t } = useI18n();
  const [target, setTarget] = useState<Target | null>(null);
  const [error, setError] = useState(false);
  const [frameFailed, setFrameFailed] = useState(false);
  useEffect(() => {
    let active = true;
    setTarget(null);
    setError(false);
    setFrameFailed(false);
    navigationService
      .target(entryPid!)
      .then((value) => {
        if (active) {
          if (value.kind !== 'embedded' || !/^https?:\/\//i.test(value.target))
            throw new Error('Invalid embedded target');
          setTarget(value);
        }
      })
      .catch(() => {
        if (active) setError(true);
      });
    return () => {
      active = false;
    };
  }, [entryPid]);
  if (error)
    return (
      <div role="alert" className="p-6">
        {t(
          'navigation.frame.denied',
          undefined,
          'This page is unavailable or you no longer have access.',
        )}
      </div>
    );
  if (!target)
    return (
      <div role="status" className="p-6">
        {t('common.loading', undefined, 'Loading…')}
      </div>
    );
  return (
    <section
      data-testid="embedded-navigation"
      className="flex h-full min-h-[70vh] flex-col gap-3 p-4"
    >
      <header className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-text-1 text-xl font-semibold">{target.name}</h1>
          <p className="text-text-2 text-sm">
            {t(
              'navigation.frame.help',
              undefined,
              'The external site manages its own sign-in and may restrict embedding. If it does not display, open it separately.',
            )}
          </p>
        </div>
        <a
          href={target.target}
          target="_blank"
          rel="noopener noreferrer"
          className="text-brand shrink-0 underline"
        >
          {t('navigation.frame.open', undefined, 'Open externally')}
        </a>
      </header>
      {frameFailed && (
        <p role="alert" className="text-text-2 text-sm">
          {t(
            'navigation.frame.failed',
            undefined,
            'The external page could not be displayed here. Use Open externally.',
          )}
        </p>
      )}
      <iframe
        title={target.name}
        src={target.target}
        referrerPolicy="no-referrer"
        sandbox="allow-scripts allow-forms allow-popups allow-same-origin"
        className="min-h-[60vh] flex-1 rounded-lg border"
        onError={() => setFrameFailed(true)}
      />
    </section>
  );
}
