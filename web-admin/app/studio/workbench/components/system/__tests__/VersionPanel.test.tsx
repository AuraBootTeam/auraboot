import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { VersionPanel } from '~/studio/workbench/components/system/VersionPanel';
import { VersionStatus, VersionType } from '~/studio/domain/version/types';

const mockVersionManager = {
  getVersions: vi.fn(),
  getCurrentVersion: vi.fn(),
  getPublishedVersion: vi.fn(),
  createVersion: vi.fn(),
  publishVersion: vi.fn(),
  rollbackVersion: vi.fn(),
  archiveVersion: vi.fn(),
};

vi.mock('~/studio/services/managers', () => ({
  getVersionManager: () => mockVersionManager,
}));

const baseVersion = {
  id: 'v1',
  version: '1.0.0',
  status: VersionStatus.draft,
  type: VersionType.PATCH,
  schema: {} as any,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  createdBy: 'tester',
  updatedBy: 'tester',
  description: '初始版本',
  changelog: 'First release',
};

describe('VersionPanel (studio)', () => {
  beforeEach(() => {
    (window as any).confirm = vi.fn().mockReturnValue(true);

    mockVersionManager.getVersions.mockResolvedValue({
      versions: [baseVersion],
      pagination: { page: 1, size: 20, total: 1, totalPages: 1 },
    });
    mockVersionManager.getCurrentVersion.mockResolvedValue(baseVersion);
    mockVersionManager.getPublishedVersion.mockResolvedValue(null);
    mockVersionManager.createVersion.mockResolvedValue({
      ...baseVersion,
      id: 'v2',
      version: '1.0.1',
    });
    mockVersionManager.publishVersion.mockResolvedValue(undefined);
    mockVersionManager.rollbackVersion.mockResolvedValue(baseVersion);
    mockVersionManager.archiveVersion.mockResolvedValue(undefined);

    vi.clearAllMocks();
  });

  it('loads versions and renders list', async () => {
    render(<VersionPanel pageId="page-1" />);

    await waitFor(() => expect(mockVersionManager.getVersions).toHaveBeenCalled());

    expect(screen.getByText('版本管理')).toBeInTheDocument();
    expect(screen.getByText('1.0.0')).toBeInTheDocument();
    expect(screen.getAllByText('初始版本').length).toBeGreaterThan(0);
  });

  it('creates a new version', async () => {
    render(<VersionPanel pageId="page-1" />);

    await waitFor(() => expect(mockVersionManager.getVersions).toHaveBeenCalled());

    fireEvent.click(screen.getAllByText('新建版本')[0]);
    fireEvent.change(screen.getByPlaceholderText('简要描述此版本的变更...'), {
      target: { value: '修复缺陷' },
    });
    fireEvent.change(screen.getByPlaceholderText('详细的变更记录...'), {
      target: { value: '修复字段校验' },
    });

    fireEvent.click(screen.getByText('创建版本'));
    await waitFor(() => expect(mockVersionManager.createVersion).toHaveBeenCalled());
  });
});
