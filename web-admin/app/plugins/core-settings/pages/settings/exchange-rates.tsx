import { useState, useEffect, useCallback } from 'react';
import { fetchResult } from '~/shared/services/http-client/HttpClient';
import { useToken as useAuthToken } from '~/contexts/AuthContext';
import { useTheme } from '~/contexts/ThemeContext';
import {
  CurrencyDollarIcon,
  PlusIcon,
  TrashIcon,
  ArrowsRightLeftIcon,
  MagnifyingGlassIcon,
} from '@heroicons/react/24/outline';

interface ExchangeRate {
  pid: string;
  baseCurrency: string;
  targetCurrency: string;
  rate: number;
  effectiveDate: string;
  source: string;
  createdAt: string;
}

interface ConversionResult {
  originalAmount: number;
  fromCurrency: string;
  convertedAmount: number;
  toCurrency: string;
  rateUsed: number;
  rateDate: string;
  triangulated: boolean;
}

export default function ExchangeRatesPage() {
  const token = useAuthToken();
  const { isDark } = useTheme();
  const [rates, setRates] = useState<ExchangeRate[]>([]);
  const [currencies, setCurrencies] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);

  // Form state
  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState({
    baseCurrency: 'usd',
    targetCurrency: 'cny',
    rate: '',
    effectiveDate: new Date().toISOString().split('T')[0],
    source: 'manual',
  });

  // Converter state
  const [converterAmount, setConverterAmount] = useState('100');
  const [converterFrom, setConverterFrom] = useState('usd');
  const [converterTo, setConverterTo] = useState('cny');
  const [conversionResult, setConversionResult] = useState<ConversionResult | null>(null);

  const fetchRates = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const result = await fetchResult<ExchangeRate[]>('/api/admin/exchange-rates/latest', {
        method: 'get',
        token: token ?? undefined,
      });
      if (result.code === '0' && result.data) {
        setRates(result.data);
      }
    } catch (err) {
      console.error('Failed to fetch rates:', err);
    } finally {
      setLoading(false);
    }
  }, [token]);

  const fetchCurrencies = useCallback(async () => {
    if (!token) return;
    try {
      const result = await fetchResult<string[]>('/api/admin/exchange-rates/currencies', {
        method: 'get',
        token: token ?? undefined,
      });
      if (result.code === '0' && result.data) {
        setCurrencies(result.data);
      }
    } catch (err) {
      console.error('Failed to fetch currencies:', err);
    }
  }, [token]);

  useEffect(() => {
    fetchRates();
    fetchCurrencies();
  }, [fetchRates, fetchCurrencies]);

  const handleSaveRate = async () => {
    if (!token || !formData.rate) return;
    try {
      const result = await fetchResult<ExchangeRate>('/api/admin/exchange-rates', {
        method: 'post',
        token: token ?? undefined,
        params: {
          baseCurrency: formData.baseCurrency,
          targetCurrency: formData.targetCurrency,
          rate: parseFloat(formData.rate),
          effectiveDate: formData.effectiveDate,
          source: formData.source,
        },
      });
      if (result.code === '0') {
        setShowForm(false);
        setFormData({ ...formData, rate: '' });
        fetchRates();
      }
    } catch (err) {
      console.error('Failed to save rate:', err);
    }
  };

  const handleDeleteRate = async (pid: string) => {
    if (!token || !confirm('Are you sure you want to delete this exchange rate?')) return;
    try {
      await fetchResult<string>(`/api/admin/exchange-rates/${pid}`, {
        method: 'delete',
        token: token ?? undefined,
      });
      fetchRates();
    } catch (err) {
      console.error('Failed to delete rate:', err);
    }
  };

  const handleConvert = async () => {
    if (!token || !converterAmount) return;
    try {
      const params = new URLSearchParams({
        amount: converterAmount,
        from: converterFrom,
        to: converterTo,
      });
      const result = await fetchResult<ConversionResult>(
        `/api/admin/exchange-rates/convert?${params.toString()}`,
        { method: 'get', token: token ?? undefined },
      );
      if (result.code === '0' && result.data) {
        setConversionResult(result.data);
      }
    } catch (err) {
      console.error('Conversion failed:', err);
    }
  };

  const cardBg = isDark ? 'bg-gray-800' : 'bg-white';
  const borderColor = isDark ? 'border-gray-700' : 'border-gray-200';
  const textPrimary = isDark ? 'text-white' : 'text-gray-900';
  const textSecondary = isDark ? 'text-gray-400' : 'text-gray-500';
  const inputBg = isDark
    ? 'bg-gray-700 text-white border-gray-600'
    : 'bg-white text-gray-900 border-gray-300';

  return (
    <div className="space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <CurrencyDollarIcon className="h-8 w-8 text-blue-500" />
          <div>
            <h1 className={`text-2xl font-bold ${textPrimary}`}>Exchange Rates</h1>
            <p className={textSecondary}>Manage currency exchange rates and conversions</p>
          </div>
        </div>
        <button
          onClick={() => setShowForm(!showForm)}
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
        >
          <PlusIcon className="h-5 w-5" />
          Add Rate
        </button>
      </div>

      {/* Add Rate Form */}
      {showForm && (
        <div className={`${cardBg} rounded-lg border ${borderColor} p-6`}>
          <h2 className={`mb-4 text-lg font-semibold ${textPrimary}`}>Add/Update Exchange Rate</h2>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-5">
            <div>
              <label className={`mb-1 block text-sm font-medium ${textSecondary}`}>
                Base Currency
              </label>
              <select
                value={formData.baseCurrency}
                onChange={(e) => setFormData({ ...formData, baseCurrency: e.target.value })}
                className={`w-full rounded-md px-3 py-2 ${inputBg}`}
              >
                {currencies.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className={`mb-1 block text-sm font-medium ${textSecondary}`}>
                Target Currency
              </label>
              <select
                value={formData.targetCurrency}
                onChange={(e) => setFormData({ ...formData, targetCurrency: e.target.value })}
                className={`w-full rounded-md px-3 py-2 ${inputBg}`}
              >
                {currencies.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className={`mb-1 block text-sm font-medium ${textSecondary}`}>Rate</label>
              <input
                type="number"
                step="0.00000001"
                value={formData.rate}
                onChange={(e) => setFormData({ ...formData, rate: e.target.value })}
                placeholder="e.g. 7.245"
                className={`w-full rounded-md px-3 py-2 ${inputBg}`}
              />
            </div>
            <div>
              <label className={`mb-1 block text-sm font-medium ${textSecondary}`}>
                Effective Date
              </label>
              <input
                type="date"
                value={formData.effectiveDate}
                onChange={(e) => setFormData({ ...formData, effectiveDate: e.target.value })}
                className={`w-full rounded-md px-3 py-2 ${inputBg}`}
              />
            </div>
            <div className="flex items-end">
              <button
                onClick={handleSaveRate}
                className="w-full rounded-md bg-green-600 px-4 py-2 text-white transition-colors hover:bg-green-700"
              >
                Save
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Currency Converter */}
      <div className={`${cardBg} rounded-lg border ${borderColor} p-6`}>
        <h2 className={`mb-4 text-lg font-semibold ${textPrimary}`}>
          <ArrowsRightLeftIcon className="mr-2 inline h-5 w-5" />
          Currency Converter
        </h2>
        <div className="flex flex-wrap items-end gap-4">
          <div>
            <label className={`mb-1 block text-sm font-medium ${textSecondary}`}>Amount</label>
            <input
              type="number"
              value={converterAmount}
              onChange={(e) => setConverterAmount(e.target.value)}
              className={`w-32 rounded-md px-3 py-2 ${inputBg}`}
            />
          </div>
          <div>
            <label className={`mb-1 block text-sm font-medium ${textSecondary}`}>From</label>
            <select
              value={converterFrom}
              onChange={(e) => setConverterFrom(e.target.value)}
              className={`w-24 rounded-md px-3 py-2 ${inputBg}`}
            >
              {currencies.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </div>
          <ArrowsRightLeftIcon className={`h-5 w-5 ${textSecondary} mb-2`} />
          <div>
            <label className={`mb-1 block text-sm font-medium ${textSecondary}`}>To</label>
            <select
              value={converterTo}
              onChange={(e) => setConverterTo(e.target.value)}
              className={`w-24 rounded-md px-3 py-2 ${inputBg}`}
            >
              {currencies.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </div>
          <button
            onClick={handleConvert}
            className="flex items-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
          >
            <MagnifyingGlassIcon className="h-4 w-4" />
            Convert
          </button>
          {conversionResult && (
            <div
              className={`ml-4 rounded-md px-4 py-2 ${isDark ? 'bg-blue-900/30' : 'bg-blue-50'}`}
            >
              <span className={`text-lg font-bold ${textPrimary}`}>
                {Number(conversionResult.convertedAmount).toFixed(2)} {conversionResult.toCurrency}
              </span>
              <span className={`ml-2 text-sm ${textSecondary}`}>
                (Rate: {conversionResult.rateUsed}
                {conversionResult.triangulated ? ' [triangulated]' : ''})
              </span>
            </div>
          )}
        </div>
      </div>

      {/* Rates Table */}
      <div className={`${cardBg} rounded-lg border ${borderColor} overflow-hidden`}>
        <div className="${borderColor} border-b px-6 py-4">
          <h2 className={`text-lg font-semibold ${textPrimary}`}>Current Exchange Rates</h2>
        </div>
        {loading ? (
          <div className="flex h-32 items-center justify-center">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-500" />
          </div>
        ) : rates.length === 0 ? (
          <div className={`p-8 text-center ${textSecondary}`}>
            No exchange rates configured yet. Click "Add Rate" to get started.
          </div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className={isDark ? 'bg-gray-700/50' : 'bg-gray-50'}>
                <th
                  className={`px-6 py-3 text-left text-xs font-medium tracking-wider uppercase ${textSecondary}`}
                >
                  Base
                </th>
                <th
                  className={`px-6 py-3 text-left text-xs font-medium tracking-wider uppercase ${textSecondary}`}
                >
                  Target
                </th>
                <th
                  className={`px-6 py-3 text-right text-xs font-medium tracking-wider uppercase ${textSecondary}`}
                >
                  Rate
                </th>
                <th
                  className={`px-6 py-3 text-left text-xs font-medium tracking-wider uppercase ${textSecondary}`}
                >
                  Effective Date
                </th>
                <th
                  className={`px-6 py-3 text-left text-xs font-medium tracking-wider uppercase ${textSecondary}`}
                >
                  Source
                </th>
                <th
                  className={`px-6 py-3 text-center text-xs font-medium tracking-wider uppercase ${textSecondary}`}
                >
                  Actions
                </th>
              </tr>
            </thead>
            <tbody className={`divide-y ${borderColor}`}>
              {rates.map((rate) => (
                <tr key={rate.pid} className={isDark ? 'hover:bg-gray-700/30' : 'hover:bg-gray-50'}>
                  <td className={`px-6 py-4 font-medium ${textPrimary}`}>
                    <span className="inline-flex items-center gap-1">
                      <CurrencyDollarIcon className="h-4 w-4 text-green-500" />
                      {rate.baseCurrency}
                    </span>
                  </td>
                  <td className={`px-6 py-4 ${textPrimary}`}>{rate.targetCurrency}</td>
                  <td className={`px-6 py-4 text-right font-mono ${textPrimary}`}>
                    {Number(rate.rate).toFixed(6)}
                  </td>
                  <td className={`px-6 py-4 ${textSecondary}`}>{rate.effectiveDate}</td>
                  <td className={`px-6 py-4 ${textSecondary}`}>
                    <span
                      className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${
                        rate.source === 'manual'
                          ? 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-300'
                          : 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-300'
                      }`}
                    >
                      {rate.source}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-center">
                    <button
                      onClick={() => handleDeleteRate(rate.pid)}
                      className="text-red-500 transition-colors hover:text-red-700"
                      title="Delete rate"
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
