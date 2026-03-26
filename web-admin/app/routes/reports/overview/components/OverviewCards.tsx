import { ArrowUpIcon, ArrowDownIcon } from '@heroicons/react/24/outline';
import type { StatisticData } from '~/routes/reports/overview/types';

// 概览卡片数据类型
interface OverviewCardData {
  label: string;
  value: string | number;
  trend?: 'up' | 'down' | 'stable';
  changePercent?: number;
}

interface OverviewCardsProps {
  data: OverviewCardData[];
  loading?: boolean;
}

export function OverviewCards({ data, loading = false }: OverviewCardsProps) {
  if (loading) {
    return (
      <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, index) => (
          <div key={index} className="animate-pulse rounded-lg bg-white p-6 shadow">
            <div className="mb-2 h-4 w-3/4 rounded bg-gray-200"></div>
            <div className="mb-2 h-8 w-1/2 rounded bg-gray-200"></div>
            <div className="h-3 w-full rounded bg-gray-200"></div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-4">
      {data.map((item, index) => (
        <div key={index} className="rounded-lg bg-white p-6 shadow">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-gray-600">{item.label}</p>
              <p className="text-2xl font-bold text-gray-900">{item.value}</p>
            </div>
            <div
              className={`rounded-full p-3 ${
                item.trend === 'up'
                  ? 'bg-green-100'
                  : item.trend === 'down'
                    ? 'bg-red-100'
                    : 'bg-gray-100'
              }`}
            >
              {item.trend === 'up' && <ArrowUpIcon className="h-6 w-6 text-green-600" />}
              {item.trend === 'down' && <ArrowDownIcon className="h-6 w-6 text-red-600" />}
              {item.trend === 'stable' && <div className="h-6 w-6 rounded-full bg-gray-400" />}
            </div>
          </div>
          {item.changePercent && (
            <div className="mt-4">
              <span
                className={`text-sm ${
                  item.trend === 'up'
                    ? 'text-green-600'
                    : item.trend === 'down'
                      ? 'text-red-600'
                      : 'text-gray-600'
                }`}
              >
                {item.trend === 'up' ? '+' : ''}
                {item.changePercent}%
              </span>
              <span className="ml-1 text-sm text-gray-500">vs 上期</span>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
