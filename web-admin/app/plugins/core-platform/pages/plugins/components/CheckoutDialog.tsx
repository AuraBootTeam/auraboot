import { useState } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import { XMarkIcon, CreditCardIcon, GiftIcon } from '@heroicons/react/24/outline';

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
  pluginId: string;
  pluginName: string;
  plans: PlanInfo[];
  locale: string;
  onClose: () => void;
  onTrialStarted: () => void;
}

export default function CheckoutDialog({
  pluginId,
  pluginName,
  plans,
  locale,
  onClose,
  onTrialStarted,
}: CheckoutDialogProps) {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [processing, setProcessing] = useState(false);

  const handleStartTrial = async () => {
    setProcessing(true);
    try {
      const res = await fetch(`/api/entitlements/${encodeURIComponent(pluginId)}/activate`, {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      const data = await res.json();
      if (res.ok && data.code === '0') {
        showSuccessToast(
          locale === 'zh-CN' ? '试用已开始！有效期 30 天' : 'Trial started! Valid for 30 days',
        );
        onTrialStarted();
      } else {
        showErrorToast(data.message || 'Failed to start trial');
      }
    } catch {
      showErrorToast('Failed to start trial');
    } finally {
      setProcessing(false);
    }
  };

  const handleCheckout = async (plan: PlanInfo) => {
    setProcessing(true);
    try {
      const res = await fetch('/api/payment/checkout', {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          pluginId,
          planCode: plan.planCode,
          billingType: plan.billingType,
        }),
      });
      const data = await res.json();
      if (res.ok && data.code === '0') {
        const checkoutUrl = data.data?.checkoutUrl;
        if (checkoutUrl) {
          window.location.href = checkoutUrl;
        }
      } else {
        showErrorToast(data.message || 'Failed to create checkout');
      }
    } catch {
      showErrorToast('Checkout failed');
    } finally {
      setProcessing(false);
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

  const paidPlans = plans.filter((p) => p.billingType !== 'free' && (p.priceAmount ?? 0) > 0);
  const hasTrialEligibility = plans.some((p) => (p.trialDays ?? 0) > 0);

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
          {/* Trial option */}
          {hasTrialEligibility && (
            <div className="rounded-lg border border-blue-200 bg-blue-50/50 p-4">
              <div className="flex items-center gap-3">
                <GiftIcon className="h-6 w-6 text-blue-600" />
                <div className="flex-1">
                  <p className="font-medium text-gray-900">
                    {locale === 'zh-CN' ? '免费试用 30 天' : '30-Day Free Trial'}
                  </p>
                  <p className="text-sm text-gray-500">
                    {locale === 'zh-CN'
                      ? '无需信用卡，立即体验全部功能'
                      : 'No credit card required. Try all features now.'}
                  </p>
                </div>
                <button
                  onClick={handleStartTrial}
                  disabled={processing}
                  className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                >
                  {processing
                    ? locale === 'zh-CN'
                      ? '处理中...'
                      : 'Processing...'
                    : locale === 'zh-CN'
                      ? '开始试用'
                      : 'Start Trial'}
                </button>
              </div>
            </div>
          )}

          {/* Paid plans */}
          {paidPlans.length > 0 && (
            <>
              {hasTrialEligibility && (
                <div className="flex items-center gap-4 text-sm text-gray-400">
                  <div className="flex-1 border-t" />
                  <span>{locale === 'zh-CN' ? '或直接购买' : 'or purchase directly'}</span>
                  <div className="flex-1 border-t" />
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
                    <button
                      onClick={() => handleCheckout(plan)}
                      disabled={processing}
                      className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
                    >
                      <CreditCardIcon className="h-4 w-4" />
                      {locale === 'zh-CN' ? '购买' : 'Buy Now'}
                    </button>
                  </div>
                );
              })}
            </>
          )}
        </div>

        {/* Footer */}
        <div className="border-t border-gray-100 px-6 py-3 text-center text-xs text-gray-400">
          {locale === 'zh-CN'
            ? '支持信用卡、支付宝、微信支付 · 由 Stripe 安全处理'
            : 'Supports Credit Card, Alipay, WeChat Pay · Secured by Stripe'}
        </div>
      </div>
    </div>
  );
}
