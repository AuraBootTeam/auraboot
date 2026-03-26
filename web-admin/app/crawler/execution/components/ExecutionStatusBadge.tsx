interface ExecutionStatusBadgeProps {
  status: 'pending' | 'running' | 'success' | 'failed' | 'cancelled';
}

export default function ExecutionStatusBadge({ status }: ExecutionStatusBadgeProps) {
  const getStatusConfig = () => {
    switch (status) {
      case 'pending':
        return {
          color: 'badge-ghost',
          text: '等待中',
          icon: '⏳',
        };
      case 'running':
        return {
          color: 'badge-info',
          text: '运行中',
          icon: '▶️',
        };
      case 'success':
        return {
          color: 'badge-success',
          text: '成功',
          icon: '✅',
        };
      case 'failed':
        return {
          color: 'badge-error',
          text: '失败',
          icon: '❌',
        };
      case 'cancelled':
        return {
          color: 'badge-warning',
          text: '已取消',
          icon: '🚫',
        };
      default:
        return {
          color: 'badge-ghost',
          text: status,
          icon: '❓',
        };
    }
  };

  const config = getStatusConfig();

  return (
    <div className="flex items-center gap-2">
      <span className={`badge ${config.color} badge-lg gap-2`}>
        <span>{config.icon}</span>
        <span>{config.text}</span>
      </span>
      {status === 'running' && <span className="loading loading-dots loading-sm"></span>}
    </div>
  );
}
