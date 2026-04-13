import { useState, useEffect } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  CreditCardIcon,
  CheckCircleIcon,
  ClockIcon,
  XCircleIcon,
} from '@heroicons/react/24/outline';

interface PaymentOrder {
  pid: string;
  pluginId: string;
  planCode: string;
  billingType: string;
  amount: number;
  currency: string;
  status: string;
  paidAt?: string;
  createdAt: string;
}

const statusConfig: Record<string, { icon: typeof CheckCircleIcon; color: string }> = {
  paid: { icon: CheckCircleIcon, color: 'text-green-600 bg-green-50' },
  pending: { icon: ClockIcon, color: 'text-yellow-600 bg-yellow-50' },
  failed: { icon: XCircleIcon, color: 'text-red-600 bg-red-50' },
  cancelled: { icon: XCircleIcon, color: 'text-gray-600 bg-gray-50' },
  refunded: { icon: XCircleIcon, color: 'text-blue-600 bg-blue-50' },
};

export default function BillingPage() {
  const { locale } = useI18n();
  const [orders, setOrders] = useState<PaymentOrder[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    (async () => {
      try {
        const res = await fetch('/api/payment/billing/history');
        if (res.ok) {
          const json = await res.json();
          setOrders(json.data ?? []);
        }
      } catch {
        // silent
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const formatPrice = (amount: number, currency: string) => {
    return new Intl.NumberFormat(locale === 'zh-CN' ? 'zh-CN' : 'en-US', {
      style: 'currency',
      currency: currency?.toUpperCase() || 'usd',
    }).format(amount / 100);
  };

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleDateString(locale === 'zh-CN' ? 'zh-CN' : 'en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  };

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <div className="mb-6 flex items-center gap-3">
        <CreditCardIcon className="h-7 w-7 text-gray-400" />
        <h1 className="text-2xl font-bold text-gray-900">
          {locale === 'zh-CN' ? '账单与支付' : 'Billing & Payments'}
        </h1>
      </div>

      {loading ? (
        <div className="py-12 text-center text-gray-400">
          {locale === 'zh-CN' ? '加载中...' : 'Loading...'}
        </div>
      ) : orders.length === 0 ? (
        <div className="py-12 text-center">
          <CreditCardIcon className="mx-auto mb-3 h-12 w-12 text-gray-300" />
          <p className="text-gray-500">
            {locale === 'zh-CN' ? '暂无支付记录' : 'No payment history'}
          </p>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
          <table className="w-full">
            <thead>
              <tr className="bg-gray-50 text-left text-sm text-gray-500">
                <th className="px-6 py-3 font-medium">{locale === 'zh-CN' ? '日期' : 'Date'}</th>
                <th className="px-6 py-3 font-medium">{locale === 'zh-CN' ? '插件' : 'Plugin'}</th>
                <th className="px-6 py-3 font-medium">{locale === 'zh-CN' ? '计划' : 'Plan'}</th>
                <th className="px-6 py-3 font-medium">{locale === 'zh-CN' ? '金额' : 'Amount'}</th>
                <th className="px-6 py-3 font-medium">{locale === 'zh-CN' ? '状态' : 'Status'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {orders.map((order) => {
                const cfg = statusConfig[order.status] || statusConfig.PENDING;
                const StatusIcon = cfg.icon;
                return (
                  <tr key={order.pid} className="text-sm">
                    <td className="px-6 py-4 text-gray-700">
                      {formatDate(order.paidAt || order.createdAt)}
                    </td>
                    <td className="px-6 py-4 font-medium text-gray-900">{order.pluginId}</td>
                    <td className="px-6 py-4 text-gray-500">{order.planCode}</td>
                    <td className="px-6 py-4 text-gray-900">
                      {formatPrice(order.amount, order.currency)}
                    </td>
                    <td className="px-6 py-4">
                      <span
                        className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium ${cfg.color}`}
                      >
                        <StatusIcon className="h-3.5 w-3.5" />
                        {order.status}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
