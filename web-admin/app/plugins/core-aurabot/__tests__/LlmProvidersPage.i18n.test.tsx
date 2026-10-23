import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

import { I18nProvider } from '~/contexts/I18nContext';
import LlmProvidersPage from '../pages/aurabot/providers';

const state = vi.hoisted(() => ({
  platformAdmin: true,
  canManage: true,
  post: vi.fn(),
  successToast: vi.fn(),
  errorToast: vi.fn(),
  toggleEnabled: vi.fn(),
  configs: [] as any[],
  useCloudConfigs: vi.fn(),
  save: vi.fn().mockResolvedValue(true),
  readbackPending: false,
  retryReadback: vi.fn().mockResolvedValue(true),
}));

vi.mock('~/shared/services/http-client', async (importOriginal) => ({
  ...await importOriginal<typeof import('~/shared/services/http-client')>(), post: state.post,
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: state.successToast,
    showErrorToast: state.errorToast,
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
      hasPermission: () => state.canManage,
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
      handleToggleEnabled: state.toggleEnabled,
      handleSave: state.save,
      saveReadbackPending: state.readbackPending,
      retrySaveReadback: state.retryReadback,
    };
    },
  };
});

afterEach(() => {
  document.body.innerHTML = '';
  state.post.mockReset();
  state.successToast.mockReset();
  state.errorToast.mockReset();
  state.toggleEnabled.mockReset();
  state.platformAdmin = true;
  state.canManage = true;
  state.configs = [];
  state.save.mockReset().mockResolvedValue(true);
  state.useCloudConfigs.mockClear();
  state.readbackPending = false;
  state.retryReadback.mockReset().mockResolvedValue(true);
});

describe('LlmProvidersPage i18n', () => {
  it.each([
    ['zh-CN', '无权访问', '你没有管理模型服务的权限，请联系管理员。'],
    ['en-US', 'Access denied', 'You do not have permission to manage model services. Contact your administrator.'],
  ])('shows a readable %s permission refusal without internal codes', (locale, title, message) => {
    state.canManage = false;
    render(<I18nProvider initialLocale={locale} initialData={{ application: { agent: { permissionTitle: title, providerPermission: message } } }}><LlmProvidersPage /></I18nProvider>);
    expect(screen.getByRole('heading', { name: title })).toBeVisible();
    expect(screen.getByText(message)).toBeVisible();
    expect(document.body).not.toHaveTextContent('ai_center');
    expect(screen.queryByTestId('add-provider-btn')).not.toBeInTheDocument();
    expect(state.useCloudConfigs).toHaveBeenCalledWith(expect.objectContaining({ enabled: false }));
  });

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
  it('locks a saved draft until explicit readback succeeds', async () => {
    state.platformAdmin = false;
    state.readbackPending = true;
    state.configs = [{ pid: 'existing-llm', configLevel: 'tenant', serviceType: 'llm', providerCode: 'openai',
      config: '{"apiKey":"****","defaultModel":"fixture-model"}', enabled: false, priority: 7 }];
    render(<I18nProvider initialLocale="zh-CN" initialData={{ cloudConfig: { save: {
      readbackPending: '配置已保存，请重新读取后继续修改。', retryReadback: '重试读取',
    } } }}><LlmProvidersPage /></I18nProvider>);
    fireEvent.click(screen.getByTestId('provider-edit-openai'));
    expect(screen.getByText('配置已保存，请重新读取后继续修改。')).toBeVisible();
    expect(screen.getByTestId('field-priority')).toBeDisabled();
    expect(screen.getByTestId('panel-save-btn')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '重试读取' }));
    await waitFor(() => expect(screen.queryByTestId('provider-edit-panel')).not.toBeInTheDocument());
    expect(state.retryReadback).toHaveBeenCalledTimes(1);
    expect(state.save).not.toHaveBeenCalled();
  });

  it('uses the LLM-only API and hides global levels for a model-service member', () => {
    state.platformAdmin = false;
    render(<I18nProvider initialLocale="en-US" initialData={{}}><LlmProvidersPage /></I18nProvider>);
    expect(state.useCloudConfigs).toHaveBeenCalledWith({ apiBase: '/api/llm-config', initialLevel: 'tenant', enabled: true });
    expect(screen.queryByTestId('level-toggle-platform')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('add-provider-btn'));
    fireEvent.click(screen.getByTestId('picker-preset-openai'));
    expect(screen.queryByRole('radio', { name: 'Platform' })).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Tenant' })).toBeChecked();
  });

  it('keeps one authoritative custom display name and saves its latest value', async () => {
    render(<I18nProvider initialLocale="en-US" initialData={{}}><LlmProvidersPage /></I18nProvider>);
    fireEvent.click(screen.getByTestId('add-provider-btn'));
    fireEvent.click(screen.getByTestId('picker-preset-custom'));
    expect(screen.getAllByTestId('custom-display-name')).toHaveLength(1);
    expect(screen.queryByTestId('field-displayName')).not.toBeInTheDocument();
    fireEvent.change(screen.getByTestId('custom-display-name'), { target: { value: 'Initial provider' } });
    fireEvent.change(screen.getByTestId('custom-display-name'), { target: { value: 'Final provider' } });
    fireEvent.change(screen.getByTestId('field-apiKey'), { target: { value: 'fixture-key' } });
    fireEvent.click(screen.getByTestId('panel-save-btn'));
    await waitFor(() => expect(state.save).toHaveBeenCalledWith(expect.objectContaining({
      providerCode: 'final-provider', config: expect.objectContaining({ displayName: 'Final provider' }), enabled: false,
    })));
  });

  it('retains the optional display name for preset providers', () => {
    render(<I18nProvider initialLocale="en-US" initialData={{}}><LlmProvidersPage /></I18nProvider>);
    fireEvent.click(screen.getByTestId('add-provider-btn'));
    fireEvent.click(screen.getByTestId('picker-preset-openai'));
    expect(screen.getAllByTestId('field-displayName')).toHaveLength(1);
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
    // The first failed request must finish before the disabled button can accept a retry.
    await waitFor(() => expect(screen.getByTestId('panel-save-btn')).toBeEnabled());
    state.save.mockResolvedValue(true);
    fireEvent.click(screen.getByTestId('panel-save-btn'));
    await waitFor(() => expect(state.save).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByTestId('provider-edit-panel')).not.toBeInTheDocument());
  });
});

describe('LLM configuration validation is not a live connection test', () => {
  const renderProvider = () => {
    state.configs = [
      {
        pid: 'existing-llm',
        configLevel: 'tenant',
        serviceType: 'llm',
        providerCode: 'openai',
        config: '{"apiKey":"****","defaultModel":"fixture-model"}',
        enabled: false,
        priority: 1,
      },
    ];
    render(
      <I18nProvider initialLocale="zh-CN" initialData={{}}>
        <LlmProvidersPage />
      </I18nProvider>,
    );
  };

  it.each([
    { code: '0', data: { status: 'error', message: 'Missing required field: apiKey' } },
    { code: '0', data: null },
    { code: '0', data: { message: 'missing status' } },
    { code: '403', desc: 'forbidden', data: { status: 'ok' } },
  ])('rejects failed or ambiguous validation without enabling the provider: %j', async (result) => {
    state.post.mockResolvedValue(result);
    renderProvider();
    fireEvent.click(screen.getByTestId('provider-test-openai'));
    await waitFor(() => expect(state.errorToast).toHaveBeenCalledOnce());
    expect(state.successToast).not.toHaveBeenCalled();
    expect(state.toggleEnabled).not.toHaveBeenCalled();
    expect(screen.getByTestId('provider-toggle-openai')).toHaveAttribute('aria-checked', 'false');
  });

  it('reports configuration-only validation and leaves activation to the owner', async () => {
    state.post.mockResolvedValue({
      code: '0',
      data: { status: 'ok', message: 'config validated' },
    });
    renderProvider();
    expect(screen.getByTestId('provider-test-openai')).toHaveAttribute('title', '校验配置');
    fireEvent.click(screen.getByTestId('provider-test-openai'));
    await waitFor(() => expect(state.successToast).toHaveBeenCalledOnce());
    expect(state.successToast.mock.calls[0][0]).toContain('未请求模型服务');
    expect(state.errorToast).not.toHaveBeenCalled();
    expect(state.toggleEnabled).not.toHaveBeenCalled();
    expect(screen.getByTestId('provider-toggle-openai')).toHaveAttribute('aria-checked', 'false');
    fireEvent.click(screen.getByTestId('provider-toggle-openai'));
    expect(state.toggleEnabled).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByTestId('provider-edit-openai'));
    expect(screen.getByTestId('panel-test-connection')).toHaveTextContent('校验配置');
  });

  it('surfaces transport failure without enabling the provider', async () => {
    state.post.mockRejectedValue(new Error('network unavailable'));
    renderProvider();
    fireEvent.click(screen.getByTestId('provider-test-openai'));
    await waitFor(() => expect(state.errorToast).toHaveBeenCalledWith('network unavailable'));
    expect(state.toggleEnabled).not.toHaveBeenCalled();
    expect(state.successToast).not.toHaveBeenCalled();
  });
});
