import { Link, Outlet, useParams } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';

export default function StorefrontLayout() {
  const { storeHandle = 'store' } = useParams();
  const { t } = useI18n();
  const storeBasePath = `/s/${storeHandle}`;

  return (
    <div className="min-h-screen bg-white text-stone-950 dark:bg-stone-950 dark:text-stone-50">
      <header className="border-b border-stone-200 bg-white dark:border-stone-800 dark:bg-stone-950">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4">
          <Link to={storeBasePath} className="text-base font-semibold">
            {t('commerce.runtime.storefront.nav.brand', { storeHandle }, storeHandle)}
          </Link>
          <nav className="flex items-center gap-5 text-sm text-stone-600 dark:text-stone-300">
            <Link to={`${storeBasePath}/search`}>
              {t('commerce.runtime.storefront.nav.search', undefined, 'Search')}
            </Link>
            <Link to={`${storeBasePath}/cart`}>
              {t('commerce.runtime.storefront.nav.cart', undefined, 'Cart')}
            </Link>
            <Link to={`${storeBasePath}/account`}>
              {t('commerce.runtime.storefront.nav.account', undefined, 'Account')}
            </Link>
          </nav>
        </div>
      </header>
      <main className="mx-auto min-h-[calc(100vh-4rem)] max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  );
}
