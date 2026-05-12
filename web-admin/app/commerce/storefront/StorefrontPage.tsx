import { Link, useLoaderData, type LoaderFunctionArgs } from 'react-router';
import {
  fetchStorefrontBootstrap,
  fetchStorefrontProduct,
  fetchStorefrontProducts,
} from '~/commerce/publicApi';
import type {
  CommerceLoadState,
  StorefrontBootstrap,
  StorefrontProductDetail,
  StorefrontProductList,
} from '~/commerce/types';
import { useI18n } from '~/contexts/I18nContext';

type StorefrontRouteKind =
  | 'home'
  | 'collection'
  | 'product'
  | 'cart'
  | 'search'
  | 'account'
  | 'order'
  | 'unknown';

interface StorefrontLoaderData {
  storeHandle: string;
  kind: StorefrontRouteKind;
  resourceHandle?: string;
  bootstrap: CommerceLoadState<StorefrontBootstrap>;
  products: CommerceLoadState<StorefrontProductList> | null;
  product: CommerceLoadState<StorefrontProductDetail> | null;
}

function resolveStorefrontRouteKind(splat?: string): {
  kind: StorefrontRouteKind;
  resourceHandle?: string;
} {
  const segments = (splat || '').split('/').filter(Boolean);
  if (segments.length === 0) return { kind: 'home' };

  const [first, second] = segments;
  if (first === 'collections') return { kind: 'collection', resourceHandle: second || 'all' };
  if (first === 'products' && second) return { kind: 'product', resourceHandle: second };
  if (first === 'cart') return { kind: 'cart' };
  if (first === 'search') return { kind: 'search' };
  if (first === 'account') return { kind: 'account' };
  if (first === 'orders' && second) return { kind: 'order', resourceHandle: second };
  return { kind: 'unknown', resourceHandle: segments.join('/') };
}

export async function loader({
  params,
  request,
}: LoaderFunctionArgs): Promise<StorefrontLoaderData> {
  const storeHandle = params.storeHandle || '';
  const { kind, resourceHandle } = resolveStorefrontRouteKind(params['*']);
  const url = new URL(request.url);
  const query = url.searchParams.get('q') || undefined;

  const bootstrap = await fetchStorefrontBootstrap(storeHandle, request);
  let products: CommerceLoadState<StorefrontProductList> | null = null;
  let product: CommerceLoadState<StorefrontProductDetail> | null = null;

  if (kind === 'home' || kind === 'collection' || kind === 'search') {
    products = await fetchStorefrontProducts(
      storeHandle,
      {
        collectionHandle: kind === 'collection' ? resourceHandle : undefined,
        query,
        pageSize: 12,
      },
      request,
    );
  }

  if (kind === 'product' && resourceHandle) {
    product = await fetchStorefrontProduct(storeHandle, resourceHandle, request);
  }

  return {
    storeHandle,
    kind,
    resourceHandle,
    bootstrap,
    products,
    product,
  };
}

export default function StorefrontPage() {
  const { storeHandle, kind, resourceHandle, bootstrap, products, product } =
    useLoaderData<typeof loader>();
  const { t } = useI18n();
  const storeBasePath = `/s/${storeHandle}`;
  const storeName = bootstrap.data?.storeName || storeHandle;

  return (
    <section className="grid gap-8 lg:grid-cols-[1.2fr_0.8fr]">
      <div className="space-y-4">
        <p className="text-sm font-medium text-stone-500 uppercase dark:text-stone-400">
          {t('commerce.runtime.storefront.eyebrow', undefined, 'Online store')}
        </p>
        <h1 className="text-4xl font-semibold tracking-normal">
          {product?.data?.title ||
            t('commerce.runtime.storefront.title', { storeHandle }, storeName)}
        </h1>
        <p className="max-w-2xl text-base leading-7 text-stone-600 dark:text-stone-300">
          {t(
            'commerce.runtime.storefront.description',
            undefined,
            'This public shell is isolated from the admin layout and is ready for theme-driven storefront pages, catalog browsing, cart, and customer account entry points.',
          )}
        </p>

        {bootstrap.error && (
          <div className="rounded border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-100">
            {t(
              'commerce.runtime.storefront.bootstrapUnavailable',
              undefined,
              'Storefront bootstrap API is not available yet.',
            )}
            <span className="ml-2 font-mono">{bootstrap.error}</span>
          </div>
        )}

        {product?.data && (
          <div className="rounded border border-stone-200 p-5 dark:border-stone-800">
            <p className="text-sm text-stone-600 dark:text-stone-300">
              {product.data.descriptionHtml ||
                t(
                  'commerce.runtime.storefront.productPending',
                  undefined,
                  'Product detail data is ready to render from the Storefront API contract.',
                )}
            </p>
          </div>
        )}

        {products?.data && (
          <div className="grid gap-3 sm:grid-cols-2">
            {products.data.items.map((item) => (
              <Link
                key={item.id}
                to={`${storeBasePath}/products/${item.handle}`}
                className="rounded border border-stone-200 p-4 text-sm transition-colors hover:border-stone-400 dark:border-stone-800 dark:hover:border-stone-600"
              >
                <span className="block font-medium">{item.title}</span>
                {item.price && (
                  <span className="mt-2 block text-stone-500 dark:text-stone-400">
                    {item.price.amount} {item.price.currencyCode}
                  </span>
                )}
              </Link>
            ))}
          </div>
        )}

        {products?.error && (
          <div className="rounded border border-stone-200 p-4 text-sm text-stone-600 dark:border-stone-800 dark:text-stone-300">
            {t(
              'commerce.runtime.storefront.productsUnavailable',
              undefined,
              'Product listing API is not available yet.',
            )}
            <span className="ml-2 font-mono">{products.error}</span>
          </div>
        )}

        {product?.error && (
          <div className="rounded border border-stone-200 p-4 text-sm text-stone-600 dark:border-stone-800 dark:text-stone-300">
            {t(
              'commerce.runtime.storefront.productUnavailable',
              undefined,
              'Product detail API is not available yet.',
            )}
            <span className="ml-2 font-mono">{product.error}</span>
          </div>
        )}
      </div>

      <div className="rounded border border-stone-200 p-5 dark:border-stone-800">
        <h2 className="text-sm font-semibold">
          {t('commerce.runtime.storefront.next.title', undefined, 'Commerce pages')}
        </h2>
        <div className="mt-3 rounded bg-stone-50 p-3 text-xs text-stone-600 dark:bg-stone-900 dark:text-stone-300">
          <span className="font-medium">
            {t('commerce.runtime.storefront.routeKind', undefined, 'Route kind')}
          </span>
          <span className="ml-2 font-mono">{kind}</span>
          {resourceHandle && <span className="ml-2 font-mono">{resourceHandle}</span>}
        </div>
        <div className="mt-4 grid gap-3 text-sm">
          <Link to={`${storeBasePath}/collections/all`}>
            {t('commerce.runtime.storefront.next.collection', undefined, 'Collection')}
          </Link>
          <Link to={`${storeBasePath}/products/sample-product`}>
            {t('commerce.runtime.storefront.next.product', undefined, 'Product detail')}
          </Link>
          <Link to={`${storeBasePath}/cart`}>
            {t('commerce.runtime.storefront.next.cart', undefined, 'Cart')}
          </Link>
        </div>
      </div>
    </section>
  );
}
