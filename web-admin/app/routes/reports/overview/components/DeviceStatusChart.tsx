import { PieChart, Pie, Cell, ResponsiveContainer, Legend, Tooltip } from 'recharts';

// 设备状态数据类型
interface DeviceStatusData {
  name: string;
  count: number;
  color?: string;
}

interface DeviceStatusChartProps {
  data: DeviceStatusData[];
  loading?: boolean;
}

const COLORS = ['#10B981', '#F59E0B', '#EF4444', '#6B7280', '#8B5CF6'];

export function DeviceStatusChart({ data, loading = false }: DeviceStatusChartProps) {
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
        <h3 className="mb-4 text-lg font-medium text-gray-900">设备状态分布</h3>
        <div className="flex h-64 items-center justify-center text-gray-500">暂无数据</div>
      </div>
    );
  }

  const renderCustomizedLabel = ({ cx, cy, midAngle, innerRadius, outerRadius, percent }: any) => {
    const RADIAN = Math.PI / 180;
    const radius = innerRadius + (outerRadius - innerRadius) * 0.5;
    const x = cx + radius * Math.cos(-midAngle * RADIAN);
    const y = cy + radius * Math.sin(-midAngle * RADIAN);

    return (
      <text
        x={x}
        y={y}
        fill="white"
        textAnchor={x > cx ? 'start' : 'end'}
        dominantBaseline="central"
        fontSize={12}
        fontWeight="bold"
      >
        {`${(percent * 100).toFixed(0)}%`}
      </text>
    );
  };

  return (
    <div className="rounded-lg bg-white p-6 shadow">
      <h3 className="mb-4 text-lg font-medium text-gray-900">设备状态分布</h3>
      <div className="h-64">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              cx="50%"
              cy="50%"
              labelLine={false}
              label={renderCustomizedLabel}
              outerRadius={80}
              fill="#8884d8"
              dataKey="count"
              nameKey="name"
            >
              {data.map((entry, index) => (
                <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
              ))}
            </Pie>
            <Tooltip
              formatter={(value: any, name: string) => [value, '数量']}
              labelFormatter={(label: string) => `状态: ${label}`}
            />
            <Legend formatter={(value: string) => value} />
          </PieChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
