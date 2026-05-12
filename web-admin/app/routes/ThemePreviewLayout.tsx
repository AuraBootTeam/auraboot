import { Outlet } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';

export default function ThemePreviewLayout() {
  const { t } = useI18n();

  return (
    <div className="min-h-screen bg-neutral-100 text-neutral-950 dark:bg-neutral-950 dark:text-neutral-50">
      <header className="border-b border-neutral-200 bg-white dark:border-neutral-800 dark:bg-neutral-900">
        <div className="mx-auto flex h-12 max-w-7xl items-center justify-between px-4">
          <span className="text-sm font-semibold">
            {t('commerce.runtime.themePreview.nav.brand', undefined, 'Theme preview')}
          </span>
          <span className="text-xs text-neutral-500 dark:text-neutral-400">
            {t('commerce.runtime.themePreview.nav.mode', undefined, 'Preview runtime')}
          </span>
        </div>
      </header>
      <main className="min-h-[calc(100vh-3rem)]">
        <Outlet />
      </main>
    </div>
  );
}
