import { useState } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import {
  XMarkIcon,
  CreditCardIcon,
  CheckCircleIcon,
  ArrowPathIcon,
} from '@heroicons/react/24/outline';

interface PlanInfo {
  pid: string;
  planCode: string;
  displayNameZh?: string;
  displayNameEn?: string;
  billingType: string;
  priceAmount?: number;
  priceCurrency?: string;
  trialDays?: number;
}

interface CheckoutDialogProps {
  pluginPid: string;
  pluginName: string;
  versionPid?: string;
  plans: PlanInfo[];
  locale: string;
  onClose: () => void;
}

type CheckoutStep = 'idle' | 'processing' | 'redeemed' | 'revoked';

interface PaidLoopResult {
  purchasePid: string;
  tokenPid: string;
  status: string;
}

export default function CheckoutDialog({
  pluginPid,
  pluginName,
  versionPid,
  plans,
  locale,
  onClose,
}: CheckoutDialogProps) {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [step, setStep] = useState<CheckoutStep>('idle');
  const [result, setResult] = useState<PaidLoopResult | null>(null);

  const postPaid = async <T,>(path: string, payload: Record<string, unknown>): Promise<T> => {
    const res = await fetch(`/api/marketplace/paid${path}`, {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const json = await res.json();
    if (!res.ok || json.code !== '0') {
      throw new Error(json.message || `Marketplace paid API failed: ${path}`);
    }
    return json.data as T;
  };

  const handleCheckout = async (plan: PlanInfo) => {
    setStep('processing');
    try {
      const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const buyerTenantPid = `ui-tenant-${suffix}`;
      const targetInstanceUrl = window.location.origin;
      const checkout = await postPaid<{ purchasePid: string; status: string }>('/checkout', {
        pluginPid,
        pricingPlanPid: plan.pid,
        buyerTenantPid,
        currency: plan.priceCurrency || 'usd',
        idempotencyKey: `ui-checkout-${suffix}`,
      });
      await postPaid('/payment-events/local-test', {
        purchasePid: checkout.purchasePid,
        provider: 'local_test',
        providerPaymentId: `ui-payment-${suffix}`,
        eventId: `ui-event-${suffix}`,
        eventType: 'payment_confirmed',
        idempotencyKey: `ui-payment-confirm-${suffix}`,
      });
      const issued = await postPaid<{
        tokenPid: string;
        token: string;
        status: string;
      }>('/install-tokens', {
        purchasePid: checkout.purchasePid,
        pluginPid,
        versionPid: versionPid || `${pluginPid}:latest`,
        buyerTenantPid,
        targetInstanceUrl,
      });
      await postPaid('/install-tokens/redeem', {
        token: issued.token,
        targetInstanceUrl,
      });
      setResult({
        purchasePid: checkout.purchasePid,
        tokenPid: issued.tokenPid,
        status: issued.status,
      });
      setStep('redeemed');
      showSuccessToast(locale === 'zh-CN' ? '购买与安装令牌已验证' : 'Purchase and token verified');
    } catch (error) {
      setStep('idle');
      showErrorToast(error instanceof Error ? error.message : 'Checkout failed');
    }
  };

  const handleRevoke = async () => {
    if (!result) return;
    setStep('processing');
    try {
      const revoked = await postPaid<{ status: string }>('/purchases/revoke', {
        purchasePid: result.purchasePid,
        reason: 'UI paid-loop verification',
      });
      setResult({ ...result, status: revoked.status });
      setStep('revoked');
      showSuccessToast(locale === 'zh-CN' ? '购买已撤销' : 'Purchase revoked');
    } catch (error) {
      setStep('redeemed');
      showErrorToast(error instanceof Error ? error.message : 'Revoke failed');
    }
  };

  const formatPrice = (amount?: number, currency?: string) => {
    if (!amount) return locale === 'zh-CN' ? '免费' : 'Free';
    const value = amount / 100;
    return new Intl.NumberFormat(locale === 'zh-CN' ? 'zh-CN' : 'en-US', {
      style: 'currency',
      currency: currency?.toUpperCase() || 'usd',
    }).format(value);
  };

  const paidPlans =
    plans.filter((p) => p.billingType !== 'free' && (p.priceAmount ?? 0) > 0).length > 0
      ? plans.filter((p) => p.billingType !== 'free' && (p.priceAmount ?? 0) > 0)
      : [
          {
            pid: `${pluginPid}:local-test-plan`,
            planCode: 'local_test',
            displayNameZh: '本地测试购买',
            displayNameEn: 'Local Test Purchase',
            billingType: 'one_time',
            priceAmount: 9900,
            priceCurrency: 'usd',
          },
        ];
  const processing = step === 'processing';

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={onClose}
    >
      <div
        className="mx-4 w-full max-w-lg rounded-xl bg-white shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <h2 className="text-lg font-semibold text-gray-900">
            {locale === 'zh-CN' ? `获取 ${pluginName}` : `Get ${pluginName}`}
          </h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <XMarkIcon className="h-5 w-5" />
          </button>
        </div>

        {/* Body */}
        <div className="space-y-4 px-6 py-4">
          {result && (
            <div
              className="rounded-lg border border-green-200 bg-green-50 p-4"
              data-testid="marketplace-paid-result"
            >
              <div className="flex items-start gap-3">
                <CheckCircleIcon className="mt-0.5 h-6 w-6 text-green-600" />
                <div className="min-w-0 flex-1">
                  <p className="font-medium text-green-900">
                    {step === 'revoked'
                      ? locale === 'zh-CN'
                        ? '购买已撤销'
                        : 'Purchase revoked'
                      : locale === 'zh-CN'
                        ? '令牌已兑换'
                        : 'Token redeemed'}
                  </p>
                  <p className="mt-1 truncate text-xs text-green-800">
                    purchasePid: <span className="font-mono">{result.purchasePid}</span>
                  </p>
                  <p className="truncate text-xs text-green-800">
                    tokenPid: <span className="font-mono">{result.tokenPid}</span>
                  </p>
                </div>
              </div>
            </div>
          )}

          {paidPlans.map((plan) => {
            const name =
              locale === 'zh-CN'
                ? plan.displayNameZh || plan.planCode
                : plan.displayNameEn || plan.planCode;
            return (
              <div
                key={plan.pid}
                className="flex items-center justify-between rounded-lg border border-gray-200 p-4"
              >
                <div>
                  <p className="font-medium text-gray-900">{name}</p>
                  <p className="text-sm text-gray-500">
                    {formatPrice(plan.priceAmount, plan.priceCurrency)}
                    {plan.billingType === 'subscription'
                      ? locale === 'zh-CN'
                        ? ' / 年'
                        : ' / year'
                      : locale === 'zh-CN'
                        ? ' 一次性'
                        : ' one-time'}
                  </p>
                </div>
                <div className="flex gap-2">
                  {result && step !== 'revoked' && (
                    <button
                      onClick={handleRevoke}
                      disabled={processing}
                      className="flex items-center gap-2 rounded-lg border border-red-200 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-50 disabled:opacity-50"
                      data-testid="marketplace-paid-revoke"
                    >
                      <ArrowPathIcon className="h-4 w-4" />
                      {locale === 'zh-CN' ? '撤销' : 'Revoke'}
                    </button>
                  )}
                  <button
                    onClick={() => handleCheckout(plan)}
                    disabled={processing}
                    className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
                    data-testid="marketplace-paid-buy"
                  >
                    <CreditCardIcon className="h-4 w-4" />
                    {processing
                      ? locale === 'zh-CN'
                        ? '处理中...'
                        : 'Processing...'
                      : locale === 'zh-CN'
                        ? '购买'
                        : 'Buy Now'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>

        <div className="border-t border-gray-100 px-6 py-3 text-center text-xs text-gray-400">
          {locale === 'zh-CN'
            ? '本地测试支付会真实调用 Marketplace paid API'
            : 'Local test checkout calls the real Marketplace paid API'}
        </div>
      </div>
    </div>
  );
}
