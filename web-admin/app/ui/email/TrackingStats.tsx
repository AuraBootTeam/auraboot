/**
 * TrackingStats — inline display of email open/click tracking data.
 *
 * Shows: "N opens · N clicks" for an outbound email message.
 * Fetches stats lazily on mount.
 */

import { useState, useEffect } from 'react';
import { useSmartText } from '~/utils/i18n';
import { getTrackingStats, type TrackingStats as TrackingStatsData } from '~/shared/services/emailService';

interface TrackingStatsProps {
  messageId: number;
}

export default function TrackingStats({ messageId }: TrackingStatsProps) {
  const st = useSmartText();
  const [stats, setStats] = useState<TrackingStatsData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getTrackingStats(messageId)
      .then(setStats)
      .catch(() => {
        /* non-critical */
      })
      .finally(() => setLoading(false));
  }, [messageId]);

  if (loading) {
    return <span className="text-xs text-gray-400 dark:text-gray-500">{st({ 'zh-CN': '正在加载邮件统计…', en: 'Loading tracking…' })}</span>;
  }

  if (!stats) return null;

  return (
    <span
      className="inline-flex items-center gap-2 rounded-full bg-gray-100 px-2.5 py-0.5 text-xs text-gray-600 dark:bg-gray-700 dark:text-gray-300"
      data-testid={`tracking-stats-${messageId}`}
    >
      <span>
        {st({ 'zh-CN': `打开 ${stats.opens} 次`, en: `${stats.opens} ${stats.opens === 1 ? 'open' : 'opens'}` })}
      </span>
      <span className="text-gray-300 dark:text-gray-600">·</span>
      <span>
        {st({ 'zh-CN': `点击 ${stats.clicks} 次`, en: `${stats.clicks} ${stats.clicks === 1 ? 'click' : 'clicks'}` })}
      </span>
    </span>
  );
}
