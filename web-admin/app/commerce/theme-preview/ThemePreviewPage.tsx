import { useLoaderData, type LoaderFunctionArgs } from 'react-router';
import type { ThemeManifest } from '~/commerce/theme/types';
import { useI18n } from '~/contexts/I18nContext';

interface ThemePreviewLoaderData {
  themeId: string;
  manifest: ThemeManifest;
}

export async function loader({ params }: LoaderFunctionArgs): Promise<ThemePreviewLoaderData> {
  const themeId = params.themeId || '';
  return {
    themeId,
    manifest: {
      themeId,
      version: '0.0.0-preview',
      name: 'Preview theme',
      sections: [],
      templates: [],
    },
  };
}

export default function ThemePreviewPage() {
  const { themeId, manifest } = useLoaderData<typeof loader>();
  const { t } = useI18n();

  return (
    <section className="mx-auto max-w-7xl px-4 py-6">
      <div className="rounded border border-neutral-200 bg-white p-5 dark:border-neutral-800 dark:bg-neutral-900">
        <p className="text-sm font-medium text-neutral-500 uppercase dark:text-neutral-400">
          {t('commerce.runtime.themePreview.eyebrow', undefined, 'Theme runtime')}
        </p>
        <h1 className="mt-2 text-2xl font-semibold">
          {t('commerce.runtime.themePreview.title', undefined, 'Theme preview')}
        </h1>
        <p className="mt-3 max-w-3xl text-sm leading-6 text-neutral-600 dark:text-neutral-300">
          {t(
            'commerce.runtime.themePreview.description',
            undefined,
            'This authenticated preview shell will bridge Theme Designer state with storefront rendering without loading the admin sidebar.',
          )}
        </p>
        <div className="mt-4 text-sm">
          <span className="font-medium">
            {t('commerce.runtime.themePreview.id', undefined, 'Theme id')}
          </span>
          <span className="ml-2 font-mono text-neutral-500 dark:text-neutral-400">
            {themeId || t('commerce.runtime.notAvailable', undefined, 'n/a')}
          </span>
        </div>
        <div className="mt-2 text-sm">
          <span className="font-medium">
            {t('commerce.runtime.themePreview.version', undefined, 'Theme version')}
          </span>
          <span className="ml-2 font-mono text-neutral-500 dark:text-neutral-400">
            {manifest.version}
          </span>
        </div>
      </div>
    </section>
  );
}
