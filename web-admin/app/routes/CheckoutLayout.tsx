import { Outlet } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';

export default function CheckoutLayout() {
  const { t } = useI18n();

  return (
    <div className="min-h-screen bg-zinc-50 text-zinc-950 dark:bg-zinc-950 dark:text-zinc-50">
      <header className="border-b border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-900">
        <div className="mx-auto flex h-14 max-w-4xl items-center px-4">
          <span className="text-sm font-semibold">
            {t('commerce.runtime.checkout.nav.brand', undefined, 'Checkout')}
          </span>
        </div>
      </header>
      <main className="mx-auto min-h-[calc(100vh-3.5rem)] max-w-4xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
