/**
 * TaskStatsCards loading skeleton test.
 *
 * While the workbench data is being fetched (loading && !data), the cards
 * must render pulse skeletons (aria-busy) instead of zero-valued cards —
 * the first paint previously showed "0" everywhere, reading as "no work"
 * rather than "loading" (UX walkthrough F2, 2026-09-08).
 */

import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';

import { TaskStatsCards } from '~/plugins/core-bpm/components/TaskStatsCards';
import type { WorkbenchData } from '~/plugins/core-bpm/services/bpmWorkbenchService';

const data: WorkbenchData = {
  todoCount: 23,
  completedCount: 63,
  startedCount: 64,
} as WorkbenchData;

describe('TaskStatsCards', () => {
  it('renders pulse skeletons while loading without data', () => {
    const { container, queryByText } = render(
      <TaskStatsCards data={null} slaWarningCount={0} loading />,
    );
    expect(container.querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(container.querySelectorAll('.animate-pulse')).toHaveLength(4);
    // Zero-valued cards must not render during loading — "0" reads as "no work".
    expect(queryByText('待办任务')).toBeNull();
    expect(queryByText('已办任务')).toBeNull();
  });

  it('renders real values once data arrives', () => {
    render(<TaskStatsCards data={data} slaWarningCount={2} loading={false} />);
    expect(screen.getByText('待办任务')).not.toBeNull();
    expect(screen.getByText('23')).not.toBeNull();
    expect(screen.getByText('63')).not.toBeNull();
    expect(screen.getByText('64')).not.toBeNull();
    expect(screen.getByText('2')).not.toBeNull();
  });
});
