import { Link, Outlet } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';

export default function MerchantLayout() {
  const { t } = useI18n();

  return (
    <div className="min-h-screen bg-slate-50 text-slate-950 dark:bg-slate-950 dark:text-slate-50">
      <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <div className="mx-auto flex h-14 max-w-7xl items-center justify-between px-4">
          <Link to="/merchant" className="text-sm font-semibold tracking-wide">
            {t('commerce.runtime.merchant.nav.brand', undefined, 'Merchant')}
          </Link>
          <nav className="flex items-center gap-4 text-sm text-slate-600 dark:text-slate-300">
            <Link to="/merchant/products">
              {t('commerce.runtime.merchant.nav.products', undefined, 'Products')}
            </Link>
            <Link to="/merchant/orders">
              {t('commerce.runtime.merchant.nav.orders', undefined, 'Orders')}
            </Link>
            <Link to="/merchant/settings">
              {t('commerce.runtime.merchant.nav.settings', undefined, 'Settings')}
            </Link>
          </nav>
        </div>
      </header>
      <main className="mx-auto min-h-[calc(100vh-3.5rem)] max-w-7xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
