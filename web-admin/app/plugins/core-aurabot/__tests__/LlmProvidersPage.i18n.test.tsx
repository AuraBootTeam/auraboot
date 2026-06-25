import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { I18nProvider } from '~/contexts/I18nContext';
import LlmProvidersPage from '../pages/aurabot/providers';

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
  }),
}));

vi.mock('~/shared/admin/cloud-config-core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('~/shared/admin/cloud-config-core')>();
  return {
    ...actual,
    useCloudConfigs: () => ({
      configs: [],
      loading: false,
      level: 'platform',
      setLevel: vi.fn(),
      testingPid: null,
      handleDelete: vi.fn(),
      handleToggleEnabled: vi.fn(),
      handleSave: vi.fn(),
    }),
  };
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('LlmProvidersPage i18n', () => {
  it('renders zh-CN provider management copy from i18n resources', () => {
    render(
      <I18nProvider
        initialLocale="zh-CN"
        initialData={{
          ai: {
            providers: {
              title: '模型服务',
              subtitle: '管理 AI 模型提供商的 API Key、模型和端点',
              count: { configured: '已配置 {count} 个提供商' },
              level: { platform: '平台', tenant: '租户' },
              action: { add: '添加提供商' },
              empty: {
                title: '暂无已配置的模型提供商',
                description: '添加 AI 模型提供商后即可启用 AuraBot 对话、AI 评分和其他智能能力。',
                addFirst: '添加第一个提供商',
              },
            },
          },
        }}
      >
        <LlmProvidersPage />
      </I18nProvider>,
    );

    expect(screen.getByRole('heading', { name: '模型服务' })).toBeInTheDocument();
    expect(screen.getByText('管理 AI 模型提供商的 API Key、模型和端点')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '添加提供商' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '平台' })).toBeInTheDocument();
    expect(screen.getByText('已配置 0 个提供商')).toBeInTheDocument();
    expect(screen.getByText('暂无已配置的模型提供商')).toBeInTheDocument();
    expect(screen.getByText('添加第一个提供商')).toBeInTheDocument();
  });
});
