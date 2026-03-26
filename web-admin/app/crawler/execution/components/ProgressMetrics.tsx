import { useEffect, useState } from 'react';

interface ProgressMetricsProps {
  startTime?: string;
  endTime?: string;
  articlesCollected: number;
  urlsProcessed: number;
  currentUrl?: string;
}

export default function ProgressMetrics({
  startTime,
  endTime,
  articlesCollected,
  urlsProcessed,
  currentUrl,
}: ProgressMetricsProps) {
  const [elapsedTime, setElapsedTime] = useState<string>('');

  useEffect(() => {
    if (!startTime) return;

    const updateElapsedTime = () => {
      const start = new Date(startTime).getTime();
      const end = endTime ? new Date(endTime).getTime() : Date.now();
      const diff = Math.floor((end - start) / 1000); // seconds

      const hours = Math.floor(diff / 3600);
      const minutes = Math.floor((diff % 3600) / 60);
      const seconds = diff % 60;

      if (hours > 0) {
        setElapsedTime(`${hours}小时 ${minutes}分钟 ${seconds}秒`);
      } else if (minutes > 0) {
        setElapsedTime(`${minutes}分钟 ${seconds}秒`);
      } else {
        setElapsedTime(`${seconds}秒`);
      }
    };

    updateElapsedTime();

    if (!endTime) {
      const interval = setInterval(updateElapsedTime, 1000);
      return () => clearInterval(interval);
    }
  }, [startTime, endTime]);

  // Calculate average speed (articles per minute)
  const getAverageSpeed = () => {
    if (!startTime || articlesCollected === 0) return 0;

    const start = new Date(startTime).getTime();
    const end = endTime ? new Date(endTime).getTime() : Date.now();
    const minutes = (end - start) / 1000 / 60;

    if (minutes === 0) return 0;
    return (articlesCollected / minutes).toFixed(2);
  };

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
        {/* Elapsed Time */}
        <div className="stat bg-base-200 rounded-lg">
          <div className="stat-title">已用时间</div>
          <div className="stat-value text-2xl">{elapsedTime || '-'}</div>
          <div className="stat-desc">{endTime ? '总时长' : '实时更新'}</div>
        </div>

        {/* Articles Collected */}
        <div className="stat bg-base-200 rounded-lg">
          <div className="stat-title">已收集文章</div>
          <div className="stat-value text-primary text-2xl">{articlesCollected}</div>
          <div className="stat-desc">平均 {getAverageSpeed()} 篇/分钟</div>
        </div>

        {/* URLs Processed */}
        <div className="stat bg-base-200 rounded-lg">
          <div className="stat-title">已处理 URL</div>
          <div className="stat-value text-secondary text-2xl">{urlsProcessed}</div>
          <div className="stat-desc">
            {urlsProcessed > 0
              ? `平均 ${(articlesCollected / urlsProcessed).toFixed(1)} 篇/URL`
              : '-'}
          </div>
        </div>

        {/* Current URL */}
        <div className="stat bg-base-200 rounded-lg">
          <div className="stat-title">当前 URL</div>
          <div className="stat-value truncate text-sm" title={currentUrl}>
            {currentUrl ? new URL(currentUrl).pathname : '-'}
          </div>
          <div className="stat-desc">{currentUrl ? '正在处理' : '等待中'}</div>
        </div>
      </div>

      {/* Progress Bar */}
      {!endTime && (
        <div className="w-full">
          <progress className="progress progress-primary w-full"></progress>
        </div>
      )}
    </div>
  );
}
