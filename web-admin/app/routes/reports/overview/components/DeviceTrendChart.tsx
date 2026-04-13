import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import type { DeviceTrendResponse } from '~/routes/reports/overview/types';

interface DeviceTrendChartProps {
  data: DeviceTrendResponse | null;
  loading?: boolean;
}

export function DeviceTrendChart({ data, loading = false }: DeviceTrendChartProps) {
  if (loading) {
    return (
      <div className="rounded-lg bg-white p-6 shadow">
        <div className="mb-4 h-4 w-1/3 animate-pulse rounded bg-gray-200"></div>
        <div className="h-64 animate-pulse rounded bg-gray-100"></div>
      </div>
    );
  }

  if (!data || !data.data || data.data.length === 0) {
    return (
      <div className="rounded-lg bg-white p-6 shadow">
        <h3 className="mb-4 text-lg font-medium text-gray-900">设备趋势分析</h3>
        <div className="flex h-64 items-center justify-center text-gray-500">暂无数据</div>
      </div>
    );
  }

  const formatXAxisLabel = (tickItem: string) => {
    const date = new Date(tickItem);
    return date.toLocaleDateString('zh-CN', {
      month: 'short',
      day: 'numeric',
    });
  };

  return (
    <div className="rounded-lg bg-white p-6 shadow">
      <h3 className="mb-4 text-lg font-medium text-gray-900">设备趋势分析</h3>
      <div className="h-64">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart
            data={data.data}
            margin={{
              top: 5,
              right: 30,
              left: 20,
              bottom: 5,
            }}
          >
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="date" tickFormatter={formatXAxisLabel} tick={{ fontSize: 12 }} />
            <YAxis tick={{ fontSize: 12 }} />
            <Tooltip
              labelFormatter={(label) => `日期: ${new Date(label).toLocaleDateString('zh-CN')}`}
              formatter={
                ((value: any, name: string) => {
                  const nameMap: Record<string, string> = {
                    total: '总设备数',
                    online: '在线设备',
                    offline: '离线设备',
                    fault: '故障设备',
                    maintenance: '维护设备',
                  };
                  return [value, nameMap[name] || name];
                }) as any
              }
            />
            <Legend
              formatter={(value: string) => {
                const nameMap: Record<string, string> = {
                  total: '总设备数',
                  online: '在线设备',
                  offline: '离线设备',
                  fault: '故障设备',
                  maintenance: '维护设备',
                };
                return nameMap[value] || value;
              }}
            />
            <Line type="monotone" dataKey="total" stroke="#3B82F6" strokeWidth={2} dot={{ r: 4 }} />
            <Line
              type="monotone"
              dataKey="online"
              stroke="#10B981"
              strokeWidth={2}
              dot={{ r: 4 }}
            />
            <Line
              type="monotone"
              dataKey="offline"
              stroke="#F59E0B"
              strokeWidth={2}
              dot={{ r: 4 }}
            />
            <Line type="monotone" dataKey="fault" stroke="#EF4444" strokeWidth={2} dot={{ r: 4 }} />
            <Line
              type="monotone"
              dataKey="maintenance"
              stroke="#8B5CF6"
              strokeWidth={2}
              dot={{ r: 4 }}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
