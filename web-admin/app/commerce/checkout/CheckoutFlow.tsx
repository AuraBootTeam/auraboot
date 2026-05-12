import { useLoaderData, type LoaderFunctionArgs } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';

interface CheckoutLoaderData {
  checkoutId: string;
  step: string;
}

export async function loader({ params }: LoaderFunctionArgs): Promise<CheckoutLoaderData> {
  const checkoutId = params.checkoutId || '';
  const step = params['*']?.split('/').filter(Boolean)[0] || 'contact';
  return { checkoutId, step };
}

export default function CheckoutFlow() {
  const { checkoutId, step } = useLoaderData<typeof loader>();
  const { t } = useI18n();

  return (
    <section className="space-y-5">
      <div>
        <p className="text-sm font-medium text-zinc-500 uppercase dark:text-zinc-400">
          {t('commerce.runtime.checkout.eyebrow', undefined, 'Secure runtime')}
        </p>
        <h1 className="mt-2 text-2xl font-semibold">
          {t('commerce.runtime.checkout.title', undefined, 'Checkout flow')}
        </h1>
      </div>
      <p className="max-w-2xl text-sm leading-6 text-zinc-600 dark:text-zinc-300">
        {t(
          'commerce.runtime.checkout.description',
          undefined,
          'This shell is reserved for checkout steps, idempotency, payment handoff, inventory reservation, and order completion.',
        )}
      </p>
      <div className="rounded border border-zinc-200 bg-white p-4 text-sm dark:border-zinc-800 dark:bg-zinc-900">
        <span className="font-medium">
          {t('commerce.runtime.checkout.id', undefined, 'Checkout id')}
        </span>
        <span className="ml-2 font-mono text-zinc-500 dark:text-zinc-400">
          {checkoutId || t('commerce.runtime.notAvailable', undefined, 'n/a')}
        </span>
      </div>
      <div className="rounded border border-zinc-200 bg-white p-4 text-sm dark:border-zinc-800 dark:bg-zinc-900">
        <span className="font-medium">
          {t('commerce.runtime.checkout.step', undefined, 'Checkout step')}
        </span>
        <span className="ml-2 font-mono text-zinc-500 dark:text-zinc-400">{step}</span>
      </div>
    </section>
  );
}
