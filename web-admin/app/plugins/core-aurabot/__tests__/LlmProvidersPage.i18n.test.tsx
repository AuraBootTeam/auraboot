import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

import { I18nProvider } from '~/contexts/I18nContext';
import LlmProvidersPage from '../pages/aurabot/providers';

const state = vi.hoisted(() => ({
  platformAdmin: true,
  configs: [] as any[],
  useCloudConfigs: vi.fn(),
  save: vi.fn().mockResolvedValue(true),
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
  }),
}));

// #1084 gated the page behind hasPermission('ai_center'); without an
// authenticated context the component renders RouteAccessDenied instead of
// the provider management copy this test asserts on.
vi.mock('~/contexts/AuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('~/contexts/AuthContext')>();
  return {
    ...actual,
    useAuth: () => ({
      user: null,
      permissions: null,
      preferences: null,
      token: null,
      isAuthenticated: true,
      hasPermission: () => true,
      hasRole: () => state.platformAdmin,
      hasAnyPermission: () => true,
      hasAllPermissions: () => true,
    }),
  };
});

vi.mock('~/shared/admin/cloud-config-core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('~/shared/admin/cloud-config-core')>();
  return {
    ...actual,
    useCloudConfigs: (options: unknown) => {
      state.useCloudConfigs(options);
      return {
      configs: state.configs,
      loading: false,
      level: state.platformAdmin ? 'platform' : 'tenant',
      setLevel: vi.fn(),
      testingPid: null,
      handleDelete: vi.fn(),
      handleToggleEnabled: vi.fn(),
      handleSave: state.save,
    };
    },
  };
});

afterEach(() => {
  document.body.innerHTML = '';
  state.platformAdmin = true;
  state.configs = [];
  state.save.mockReset().mockResolvedValue(true);
  state.useCloudConfigs.mockClear();
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


describe('LlmProvidersPage scoped configuration', () => {
  it('uses the LLM-only API and hides global levels for a model-service member', () => {
    state.platformAdmin = false;
    render(<I18nProvider initialLocale="en-US" initialData={{}}><LlmProvidersPage /></I18nProvider>);
    expect(state.useCloudConfigs).toHaveBeenCalledWith({ apiBase: '/api/llm-config', initialLevel: 'tenant' });
    expect(screen.queryByTestId('level-toggle-platform')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('add-provider-btn'));
    fireEvent.click(screen.getByTestId('picker-preset-openai'));
    expect(screen.queryByRole('radio', { name: 'Platform' })).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Tenant' })).toBeChecked();
  });

  it('saves the edited PID and retains the draft panel on failure', async () => {
    state.platformAdmin = false;
    state.configs = [{ pid: 'existing-llm', configLevel: 'tenant', serviceType: 'llm', providerCode: 'openai',
      config: '{"apiKey":"test-key","defaultModel":"fixture-model"}', enabled: false, priority: 1 }];
    state.save.mockResolvedValue(false);
    render(<I18nProvider initialLocale="en-US" initialData={{}}><LlmProvidersPage /></I18nProvider>);
    fireEvent.click(screen.getByTestId('provider-edit-openai'));
    fireEvent.change(screen.getByTestId('field-priority'), { target: { value: '7' } });
    fireEvent.click(screen.getByTestId('panel-save-btn'));
    await waitFor(() => expect(state.save).toHaveBeenCalledWith(expect.objectContaining({ pid: 'existing-llm', priority: 7, serviceType: 'llm', configLevel: 'tenant' })));
    expect(screen.getByTestId('provider-edit-panel')).toBeInTheDocument();
    expect(screen.getByTestId('field-priority')).toHaveValue(7);
    state.save.mockResolvedValue(true);
    fireEvent.click(screen.getByTestId('panel-save-btn'));
    await waitFor(() => expect(screen.queryByTestId('provider-edit-panel')).not.toBeInTheDocument());
  });
});
