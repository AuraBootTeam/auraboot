import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import type { StoreDistributionData } from '~/routes/reports/overview/types';

interface StoreDistributionChartProps {
  data: StoreDistributionData[];
  loading?: boolean;
}

export function StoreDistributionChart({ data, loading = false }: StoreDistributionChartProps) {
  if (loading) {
    return (
      <div className="rounded-lg bg-white p-6 shadow">
        <div className="mb-4 h-4 w-1/3 animate-pulse rounded bg-gray-200"></div>
        <div className="h-64 animate-pulse rounded bg-gray-100"></div>
      </div>
    );
  }

  if (!data || data.length === 0) {
    return (
      <div className="rounded-lg bg-white p-6 shadow">
        <h3 className="mb-4 text-lg font-medium text-gray-900">门店分布统计</h3>
        <div className="flex h-64 items-center justify-center text-gray-500">暂无数据</div>
      </div>
    );
  }

  const formatYAxisLabel = (tickItem: number) => {
    if (tickItem >= 1000) {
      return `${(tickItem / 1000).toFixed(1)}k`;
    }
    return tickItem.toString();
  };

  return (
    <div className="rounded-lg bg-white p-6 shadow">
      <h3 className="mb-4 text-lg font-medium text-gray-900">门店分布统计</h3>
      <div className="h-64">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={data}
            margin={{
              top: 5,
              right: 30,
              left: 20,
              bottom: 5,
            }}
          >
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis
              dataKey="region"
              tick={{ fontSize: 12 }}
              angle={-45}
              textAnchor="end"
              height={60}
            />
            <YAxis tickFormatter={formatYAxisLabel} tick={{ fontSize: 12 }} />
            <Tooltip
              formatter={(value: any, name: string) => {
                const nameMap: Record<string, string> = {
                  count: '数量',
                  deviceCount: '设备数量',
                  storeCount: '门店数量',
                };
                return [value, nameMap[name] || name];
              }}
              labelFormatter={(label) => `区域: ${label}`}
            />
            <Legend
              formatter={(value: string) => {
                const nameMap: Record<string, string> = {
                  count: '数量',
                  deviceCount: '设备数量',
                  storeCount: '门店数量',
                };
                return nameMap[value] || value;
              }}
            />
            <Bar dataKey="total" fill="#3B82F6" radius={[4, 4, 0, 0]} name="总数" />
            <Bar dataKey="active" fill="#10B981" radius={[4, 4, 0, 0]} name="活跃" />
            <Bar dataKey="inactive" fill="#EF4444" radius={[4, 4, 0, 0]} name="非活跃" />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
