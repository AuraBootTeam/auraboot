import { useState, useEffect, useCallback } from 'react';
import { get } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';

interface ContractRecord {
  pid: string;
  contract_no: string;
  contract_name: string;
  contract_type: string;
  contract_amount: number;
  received_amount: number;
  payment_rate: number;
  status: string;
  overdue_count: number;
  pending_amount: number;
}

interface ProjectContractsProps {
  projectId: string;
}

const STATUS_COLORS: Record<string, string> = {
  draft: 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-300',
  REVIEW: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
  SIGNED: 'bg-indigo-100 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300',
  EXECUTING: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
  SETTLED: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300',
  closed: 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400',
};

const STATUS_LABELS: Record<string, { zh: string; en: string }> = {
  draft: { zh: '草稿', en: 'Draft' },
  REVIEW: { zh: '审核中', en: 'Review' },
  SIGNED: { zh: '已签署', en: 'Signed' },
  EXECUTING: { zh: '执行中', en: 'Executing' },
  SETTLED: { zh: '已结算', en: 'Settled' },
  closed: { zh: '已关闭', en: 'Closed' },
};

function formatAmount(n: number): string {
  if (n >= 10000) return `¥${(n / 10000).toFixed(1)}万`;
  return `¥${n.toLocaleString()}`;
}

export default function ProjectContracts({ projectId }: ProjectContractsProps) {
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [contracts, setContracts] = useState<ContractRecord[]>([]);
  const [loading, setLoading] = useState(true);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<{ records: ContractRecord[] }>('/api/datasource/list', {
        datasourceId: 'nq:cc_contract_payment_status',
        projectId,
        format: 'records',
      });
      if (ResultHelper.isSuccess(result) && result.data?.records) {
        setContracts(result.data.records);
      }
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const totalContract = contracts.reduce((s, c) => s + (c.contract_amount || 0), 0);
  const totalReceived = contracts.reduce((s, c) => s + (c.received_amount || 0), 0);
  const totalPending = contracts.reduce((s, c) => s + (c.pending_amount || 0), 0);

  if (loading) {
    return (
      <div className="flex min-h-[200px] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
      </div>
    );
  }

  return (
    <div className="space-y-6" data-testid="project-contracts">
      {/* KPI Cards */}
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4" data-testid="contracts-kpi">
        <div className="rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
          <div className="mb-1 text-xs text-gray-500 dark:text-gray-400">
            {l('合同数量', 'Contracts')}
          </div>
          <div className="text-2xl font-bold text-gray-900 dark:text-white">{contracts.length}</div>
        </div>
        <div className="rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
          <div className="mb-1 text-xs text-gray-500 dark:text-gray-400">
            {l('合同总额', 'Total Amount')}
          </div>
          <div className="text-2xl font-bold text-blue-600 dark:text-blue-400">
            {formatAmount(totalContract)}
          </div>
        </div>
        <div className="rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
          <div className="mb-1 text-xs text-gray-500 dark:text-gray-400">
            {l('已回款', 'Received')}
          </div>
          <div className="text-2xl font-bold text-green-600 dark:text-green-400">
            {formatAmount(totalReceived)}
          </div>
        </div>
        <div className="rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
          <div className="mb-1 text-xs text-gray-500 dark:text-gray-400">
            {l('待回款', 'Pending')}
          </div>
          <div className="text-2xl font-bold text-amber-600 dark:text-amber-400">
            {formatAmount(totalPending)}
          </div>
        </div>
      </div>

      {/* Contract Table */}
      <div className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
              <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                {l('合同编号', 'Contract #')}
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                {l('合同名称', 'Name')}
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                {l('类型', 'Type')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                {l('合同金额', 'Amount')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                {l('已回款', 'Received')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                {l('回款率', 'Rate')}
              </th>
              <th className="px-4 py-3 text-center font-medium text-gray-500 dark:text-gray-400">
                {l('状态', 'Status')}
              </th>
            </tr>
          </thead>
          <tbody>
            {contracts.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-gray-400 dark:text-gray-500">
                  {l('暂无合同数据', 'No contracts found')}
                </td>
              </tr>
            ) : (
              contracts.map((c) => (
                <tr
                  key={c.pid}
                  className="border-b border-gray-100 transition-colors hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
                >
                  <td className="px-4 py-3 font-mono text-xs text-gray-600 dark:text-gray-400">
                    {c.contract_no}
                  </td>
                  <td className="px-4 py-3 font-medium text-gray-900 dark:text-white">
                    {c.contract_name}
                  </td>
                  <td className="px-4 py-3 text-gray-600 dark:text-gray-400">{c.contract_type}</td>
                  <td className="px-4 py-3 text-right font-mono text-gray-900 dark:text-white">
                    {formatAmount(c.contract_amount || 0)}
                  </td>
                  <td className="px-4 py-3 text-right font-mono text-green-600 dark:text-green-400">
                    {formatAmount(c.received_amount || 0)}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <span
                      className={`font-medium ${(c.payment_rate || 0) >= 80 ? 'text-green-600' : (c.payment_rate || 0) >= 50 ? 'text-amber-600' : 'text-red-600'}`}
                    >
                      {c.payment_rate || 0}%
                    </span>
                  </td>
                  <td className="px-4 py-3 text-center">
                    <span
                      className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_COLORS[c.status] || STATUS_COLORS.DRAFT}`}
                    >
                      {l(
                        STATUS_LABELS[c.status]?.zh || c.status,
                        STATUS_LABELS[c.status]?.en || c.status,
                      )}
                    </span>
                    {(c.overdue_count || 0) > 0 && (
                      <span className="ml-1 inline-flex rounded-full bg-red-100 px-1.5 py-0.5 text-[10px] font-medium text-red-700 dark:bg-red-900/30 dark:text-red-300">
                        {c.overdue_count} {l('逾期', 'overdue')}
                      </span>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
