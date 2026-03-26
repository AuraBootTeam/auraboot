/**
 * SLA Monitor Panel
 * Dashboard for monitoring SLA status and active records.
 * Supports drill-down: click a StatCard to expand the SlaRecordListPanel filtered by status.
 */

import { useState, useEffect, useCallback } from 'react';
import { Button } from '~/components/ui/button';
import { useSmartText } from '~/utils/i18n';
import { useToastContext } from '~/contexts/ToastContext';
import {
  RefreshCw,
  Activity,
  AlertTriangle,
  Clock,
  PauseCircle,
  XCircle,
  BarChart3,
} from 'lucide-react';
import type { DashboardData } from '../services/slaService';
import * as slaService from '../services/slaService';
import { SlaRecordListPanel } from './SlaRecordListPanel';

// ==================== Stat Card Component ====================

function StatCard({
  title,
  value,
  icon: Icon,
  color,
  onClick,
  active,
  testId,
}: {
  title: string;
  value: number;
  icon: React.ElementType;
  color: string;
  onClick?: () => void;
  active?: boolean;
  testId?: string;
}) {
  const colorClasses: Record<string, string> = {
    blue: 'bg-blue-50 text-blue-700 border-blue-200',
    green: 'bg-green-50 text-green-700 border-green-200',
    yellow: 'bg-yellow-50 text-yellow-700 border-yellow-200',
    red: 'bg-red-50 text-red-700 border-red-200',
    gray: 'bg-gray-50 text-gray-700 border-gray-200',
    purple: 'bg-purple-50 text-purple-700 border-purple-200',
  };

  return (
    <div
      className={`rounded-lg border p-4 transition-all ${colorClasses[color] || colorClasses.gray} ${
        onClick ? 'cursor-pointer hover:scale-[1.02] hover:shadow-md' : ''
      } ${active ? 'shadow-md ring-2 ring-blue-400' : ''}`}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onKeyDown={
        onClick
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') onClick();
            }
          : undefined
      }
      data-testid={testId}
    >
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm font-medium opacity-80">{title}</p>
          <p className="mt-1 text-2xl font-bold">{value}</p>
        </div>
        <Icon className="h-8 w-8 opacity-60" />
      </div>
    </div>
  );
}

// ==================== Main Component ====================

export function SlaMonitorPanel() {
  const st = useSmartText();
  const { showErrorToast } = useToastContext();
  const [dashboard, setDashboard] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [drillFilter, setDrillFilter] = useState<string | null>(null);

  const loadDashboard = useCallback(async () => {
    setLoading(true);
    try {
      const data = await slaService.getDashboard();
      setDashboard(data);
    } catch {
      showErrorToast(st('$i18n:bpm.sla.monitor.loadError') || 'Failed to load SLA monitor data');
    } finally {
      setLoading(false);
    }
  }, [showErrorToast, st]);

  useEffect(() => {
    loadDashboard();
  }, [loadDashboard]);

  const handleCardClick = (status: string) => {
    setDrillFilter((prev) => (prev === status ? null : status));
  };

  return (
    <div className="space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold">
            <BarChart3 className="h-6 w-6" />
            {st('$i18n:bpm.sla.monitor.title') || 'SLA Monitor'}
          </h1>
          <p className="text-muted-foreground mt-1 text-sm">
            {st('$i18n:bpm.sla.monitor.subtitle') || 'Real-time SLA status overview'}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={loadDashboard} data-testid="sla-refresh">
          <RefreshCw className="mr-1 h-4 w-4" />
          {st('$i18n:bpm.sla.monitor.refresh') || 'Refresh'}
        </Button>
      </div>

      {loading ? (
        <div className="text-muted-foreground py-12 text-center">
          {st('$i18n:common.loading') || 'Loading...'}
        </div>
      ) : dashboard ? (
        <>
          {/* Process Definition Stats */}
          <div data-testid="sla-dashboard-process-definitions">
            <h2 className="mb-3 text-lg font-semibold">
              {st('$i18n:bpm.sla.monitor.processDefinitions') || 'Process Definitions'}
            </h2>
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              <StatCard
                title={st('$i18n:bpm.sla.monitor.total') || 'Total'}
                value={dashboard.processDefinitions.total}
                icon={BarChart3}
                color="blue"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.draft') || 'Draft'}
                value={dashboard.processDefinitions.draft}
                icon={Clock}
                color="gray"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.deployed') || 'Deployed'}
                value={dashboard.processDefinitions.deployed}
                icon={Activity}
                color="green"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.suspended') || 'Suspended'}
                value={dashboard.processDefinitions.suspended}
                icon={PauseCircle}
                color="yellow"
              />
            </div>
          </div>

          {/* SLA Record Stats — clickable for drill-down */}
          <div data-testid="sla-dashboard-active-records">
            <h2 className="mb-3 text-lg font-semibold">
              {st('$i18n:bpm.sla.monitor.activeRecords') || 'Active SLA Records'}
            </h2>
            <div className="grid grid-cols-2 gap-4 md:grid-cols-5">
              <StatCard
                title={st('$i18n:bpm.sla.monitor.active') || 'Active'}
                value={dashboard.sla.active}
                icon={Activity}
                color="blue"
                onClick={() => handleCardClick('all')}
                active={drillFilter === 'all'}
                testId="sla-stat-ALL"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.running') || 'Running'}
                value={dashboard.sla.running}
                icon={Clock}
                color="green"
                onClick={() => handleCardClick('running')}
                active={drillFilter === 'running'}
                testId="sla-stat-running"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.warning') || 'Warning'}
                value={dashboard.sla.warning}
                icon={AlertTriangle}
                color="yellow"
                onClick={() => handleCardClick('warning')}
                active={drillFilter === 'warning'}
                testId="sla-stat-WARNING"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.overdue') || 'Overdue'}
                value={dashboard.sla.overdue}
                icon={XCircle}
                color="red"
                onClick={() => handleCardClick('overdue')}
                active={drillFilter === 'overdue'}
                testId="sla-stat-OVERDUE"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.paused') || 'Paused'}
                value={dashboard.sla.paused}
                icon={PauseCircle}
                color="purple"
                onClick={() => handleCardClick('paused')}
                active={drillFilter === 'paused'}
                testId="sla-stat-paused"
              />
            </div>
          </div>

          {/* Drill-down Panel */}
          {drillFilter && (
            <SlaRecordListPanel
              statusFilter={drillFilter === 'all' ? undefined : drillFilter}
              onClose={() => setDrillFilter(null)}
            />
          )}

          {/* SLA Config Stats */}
          <div data-testid="sla-dashboard-configs">
            <h2 className="mb-3 text-lg font-semibold">
              {st('$i18n:bpm.sla.monitor.configs') || 'SLA Configurations'}
            </h2>
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              <StatCard
                title={st('$i18n:bpm.sla.monitor.configTotal') || 'Total Configs'}
                value={dashboard.slaConfigs.total}
                icon={BarChart3}
                color="blue"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.configEnabled') || 'Enabled'}
                value={dashboard.slaConfigs.enabled}
                icon={Activity}
                color="green"
              />
            </div>
          </div>
        </>
      ) : (
        <div className="text-muted-foreground py-12 text-center">
          {st('$i18n:bpm.sla.monitor.noData') || 'No monitoring data available'}
        </div>
      )}
    </div>
  );
}
