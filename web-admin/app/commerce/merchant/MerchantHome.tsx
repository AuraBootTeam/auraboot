import { Link, useLoaderData, type LoaderFunctionArgs } from 'react-router';
import { fetchMerchantContext } from '~/commerce/merchantApi';
import type {
  CommerceLoadState,
  MerchantCommerceContext,
  MerchantOperationLink,
} from '~/commerce/types';
import { useI18n } from '~/contexts/I18nContext';

export type MerchantSection = 'overview' | string;

export interface MerchantHomeLoaderData {
  section: MerchantSection;
  context: CommerceLoadState<MerchantCommerceContext>;
}

export async function loader({ request }: LoaderFunctionArgs): Promise<MerchantHomeLoaderData> {
  const { pathname } = new URL(request.url);
  return {
    section: getMerchantSection(pathname),
    context: await fetchMerchantContext(request),
  };
}

export function getMerchantSection(pathname: string): MerchantSection {
  const segments = pathname.split('/').filter(Boolean);
  return segments[0] === 'merchant' && segments[1] ? segments[1] : 'overview';
}

const OPERATION_FALLBACKS: Record<string, string> = {
  products: 'Products',
  inventory: 'Inventory',
  orders: 'Orders',
  fulfillment: 'Fulfillment',
  settings: 'Settings',
};

export default function MerchantHome() {
  const { section, context } = useLoaderData<typeof loader>();
  const { t } = useI18n();
  const data = context.data;
  const selectedStore = data?.selectedStore;
  const operations = data?.operations ?? [];

  return (
    <section className="space-y-6">
      <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        <div>
          <p className="text-sm font-medium text-slate-500 uppercase dark:text-slate-400">
            {t('commerce.runtime.merchant.eyebrow', undefined, 'Merchant runtime')}
          </p>
          <h1 className="mt-2 text-2xl font-semibold">
            {selectedStore?.name ??
              t('commerce.runtime.merchant.title', undefined, 'Merchant workspace')}
          </h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600 dark:text-slate-300">
            {t(
              'commerce.runtime.merchant.description',
              undefined,
              'Manage commerce operations from a shell that is separate from platform administration.',
            )}
          </p>
        </div>
        {selectedStore?.storefrontPath ? (
          <Link
            to={selectedStore.storefrontPath}
            className="inline-flex h-10 items-center justify-center rounded border border-slate-300 bg-white px-3 text-sm font-medium text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800"
          >
            {t('commerce.runtime.merchant.viewStorefront', undefined, 'View storefront')}
          </Link>
        ) : null}
      </div>

      {context.error ? (
        <div className="rounded border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900 dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-100">
          <p className="font-medium">
            {t(
              'commerce.runtime.merchant.contextUnavailable',
              undefined,
              'Merchant context unavailable',
            )}
          </p>
          <p className="mt-1">{context.error}</p>
        </div>
      ) : null}

      {!context.error && !selectedStore ? (
        <div className="rounded border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
          <p className="text-sm font-medium">
            {t('commerce.runtime.merchant.emptyStore.title', undefined, 'No active store')}
          </p>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-600 dark:text-slate-300">
            {t(
              'commerce.runtime.merchant.emptyStore.description',
              undefined,
              'Create or activate a store before connecting catalog, inventory, orders, and storefront operations.',
            )}
          </p>
        </div>
      ) : null}

      {selectedStore ? (
        <div className="grid gap-4 md:grid-cols-3">
          <div className="rounded border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <p className="text-xs font-medium text-slate-500 uppercase dark:text-slate-400">
              {t('commerce.runtime.merchant.currentStore', undefined, 'Current store')}
            </p>
            <p className="mt-2 text-base font-semibold">{selectedStore.name}</p>
            <p className="mt-1 font-mono text-xs text-slate-500 dark:text-slate-400">
              {selectedStore.handle}
            </p>
          </div>
          <div className="rounded border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <p className="text-xs font-medium text-slate-500 uppercase dark:text-slate-400">
              {t('commerce.runtime.merchant.activeStores', undefined, 'Active stores')}
            </p>
            <p className="mt-2 text-base font-semibold">{data?.stores.length ?? 0}</p>
            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
              {t(
                'commerce.runtime.merchant.activeStoresHint',
                undefined,
                'Scoped to the current tenant',
              )}
            </p>
          </div>
          <div className="rounded border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <p className="text-xs font-medium text-slate-500 uppercase dark:text-slate-400">
              {t('commerce.runtime.merchant.currentSection', undefined, 'Current section')}
            </p>
            <p className="mt-2 text-base font-semibold capitalize">{section}</p>
            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
              {t('commerce.runtime.merchant.sectionHint', undefined, 'Route-aware merchant shell')}
            </p>
          </div>
        </div>
      ) : null}

      <div>
        <p className="text-sm font-medium text-slate-500 uppercase dark:text-slate-400">
          {t('commerce.runtime.merchant.operations', undefined, 'Operations')}
        </p>
        <div className="mt-3 grid gap-3 md:grid-cols-5">
          {operations.map((operation) => (
            <OperationTile
              key={operation.code}
              operation={operation}
              active={operation.code === section}
            />
          ))}
        </div>
      </div>
    </section>
  );
}

function OperationTile({
  operation,
  active,
}: {
  operation: MerchantOperationLink;
  active: boolean;
}) {
  const { t } = useI18n();
  const label = t(
    `commerce.runtime.merchant.operation.${operation.code}`,
    undefined,
    OPERATION_FALLBACKS[operation.code] ?? operation.code,
  );

  return (
    <Link
      to={operation.route}
      aria-disabled={!operation.enabled}
      className={[
        'min-h-24 rounded border bg-white p-4 text-sm transition-colors dark:bg-slate-900',
        active
          ? 'border-slate-900 text-slate-950 dark:border-slate-100 dark:text-slate-50'
          : 'border-slate-200 text-slate-700 hover:bg-slate-50 dark:border-slate-800 dark:text-slate-200 dark:hover:bg-slate-800',
        operation.enabled ? '' : 'pointer-events-none opacity-50',
      ].join(' ')}
    >
      <span className="font-medium">{label}</span>
      <span className="mt-3 block text-xs text-slate-500 dark:text-slate-400">
        {operation.route}
      </span>
    </Link>
  );
}
