/**
 * ApprovalInbox — approval task management page.
 *
 * Features:
 * - Tab bar: Pending / Approved / Rejected / All
 * - Task cards with priority badges
 * - "View & Approve" opens BpmTaskDrawer (DSL form + approval actions)
 * - Pending count badge
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  type ApprovalTaskDTO,
  getMyPendingTasks,
  getMyHistory,
} from '~/plugins/core-bpm/services/approvalService';
import { BpmTaskDrawer } from './BpmTaskDrawer';

type Tab = 'pending' | 'approved' | 'rejected' | 'all';

const PRIORITY_COLORS: Record<string, string> = {
  URGENT: 'bg-red-100 text-red-700',
  HIGH: 'bg-orange-100 text-orange-700',
  NORMAL: 'bg-blue-100 text-blue-700',
  LOW: 'bg-gray-100 text-gray-500',
};

const STATUS_COLORS: Record<string, string> = {
  pending: 'bg-yellow-100 text-yellow-700',
  approved: 'bg-green-100 text-green-700',
  rejected: 'bg-red-100 text-red-700',
  expired: 'bg-gray-100 text-gray-500',
  cancelled: 'bg-gray-100 text-gray-500',
};

export const ApprovalInbox: React.FC = () => {
  const [activeTab, setActiveTab] = useState<Tab>('pending');
  const [tasks, setTasks] = useState<ApprovalTaskDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedTaskPid, setSelectedTaskPid] = useState<string | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const loadTasks = useCallback(async () => {
    setLoading(true);
    try {
      if (activeTab === 'pending') {
        const result = await getMyPendingTasks(1, 50);
        if (result.success && result.data) {
          setTasks(result.data);
        }
      } else {
        const statusFilter = activeTab === 'all' ? undefined : activeTab;
        const result = await getMyHistory(1, 50, statusFilter);
        if (result.success && result.data) {
          setTasks(result.data.records ?? []);
        }
      }
    } catch (e) {
      console.error('Failed to load tasks:', e);
    } finally {
      setLoading(false);
    }
  }, [activeTab]);

  useEffect(() => {
    loadTasks();
  }, [loadTasks]);

  const handleOpenDrawer = (taskPid: string) => {
    setSelectedTaskPid(taskPid);
    setDrawerOpen(true);
  };

  const handleDrawerClose = () => {
    setDrawerOpen(false);
    setSelectedTaskPid(null);
  };

  const handleActionComplete = () => {
    handleDrawerClose();
    loadTasks();
  };

  const formatTimeAgo = (dateStr: string) => {
    const diff = Date.now() - new Date(dateStr).getTime();
    const minutes = Math.floor(diff / 60000);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    return `${days}d ago`;
  };

  const tabs: { key: Tab; label: string }[] = [
    { key: 'pending', label: 'Pending' },
    { key: 'approved', label: 'Approved' },
    { key: 'rejected', label: 'Rejected' },
    { key: 'all', label: 'All' },
  ];

  return (
    <div className="w-full p-6">
      <h1 className="mb-6 text-2xl font-semibold">My Approvals</h1>

      {/* Tab bar */}
      <div className="mb-6 flex gap-1 border-b border-gray-200">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            type="button"
            onClick={() => setActiveTab(tab.key)}
            className={`border-b-2 px-4 py-2 text-sm font-medium transition-colors ${
              activeTab === tab.key
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Task list */}
      {loading ? (
        <div className="py-12 text-center text-gray-400">Loading...</div>
      ) : tasks.length === 0 ? (
        <div className="py-12 text-center text-gray-400">No tasks found</div>
      ) : (
        <div className="space-y-3">
          {tasks.map((task) => (
            <div
              key={task.pid}
              className="rounded-lg border border-gray-200 bg-white p-4 transition-shadow hover:shadow-sm"
            >
              <div className="flex items-start justify-between">
                <div className="min-w-0 flex-1">
                  <div className="mb-1 flex items-center gap-2">
                    <span
                      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                        PRIORITY_COLORS[task.priority] ?? PRIORITY_COLORS.NORMAL
                      }`}
                    >
                      {task.priority}
                    </span>
                    <span
                      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                        STATUS_COLORS[task.status] ?? STATUS_COLORS.PENDING
                      }`}
                    >
                      {task.status}
                    </span>
                    {task.deadlineAt && task.status === 'pending' && (
                      <span className="text-xs text-red-500">
                        Due: {new Date(task.deadlineAt).toLocaleDateString()}
                      </span>
                    )}
                  </div>
                  <h3 className="truncate text-base font-medium text-gray-900">{task.taskTitle}</h3>
                  {task.taskDescription && (
                    <p className="mt-0.5 truncate text-sm text-gray-500">{task.taskDescription}</p>
                  )}
                  <div className="mt-2 flex items-center gap-3 text-xs text-gray-400">
                    {task.businessKey && <span>Key: {task.businessKey}</span>}
                    <span>{formatTimeAgo(task.createdAt)}</span>
                    {task.assigneeStrategy === 'all' && (
                      <span className="text-orange-500">
                        Requires all ({task.assigneeUserIds?.length ?? 0}) approvers
                      </span>
                    )}
                  </div>
                </div>
                <div className="ml-4 flex-shrink-0">
                  {task.status === 'pending' ? (
                    <button
                      type="button"
                      onClick={() => handleOpenDrawer(task.pid)}
                      className="rounded bg-blue-500 px-3 py-1.5 text-sm text-white transition-colors hover:bg-blue-600"
                    >
                      View & Approve
                    </button>
                  ) : (
                    <button
                      type="button"
                      onClick={() => handleOpenDrawer(task.pid)}
                      className="rounded bg-gray-100 px-3 py-1.5 text-sm text-gray-600 transition-colors hover:bg-gray-200"
                    >
                      View
                    </button>
                  )}
                </div>
              </div>
              {task.approvalComment && task.status !== 'pending' && (
                <div className="mt-2 rounded bg-gray-50 p-2 text-sm text-gray-500">
                  Comment: {task.approvalComment}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Approval drawer */}
      {selectedTaskPid && (
        <BpmTaskDrawer
          taskId={selectedTaskPid}
          open={drawerOpen}
          onClose={handleDrawerClose}
          onComplete={handleActionComplete}
        />
      )}
    </div>
  );
};
