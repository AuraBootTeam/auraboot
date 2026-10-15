import {
  act,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ConfigCard,
  ConfigEditorModal,
  PROVIDERS_BY_TYPE,
  PROVIDER_FIELDS,
  cloudProviderLabel,
  providerFormValues,
  serializeProviderConfig,
  useCloudConfigs,
  type CloudConfig,
} from '../cloud-config-core';

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
}));
vi.mock('~/shared/services/http-client', () => ({
  get: http.get,
  post: http.post,
  del: http.del,
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showErrorToast: http.error,
    showSuccessToast: http.success,
  }),
}));
const binding = {
  openId: 'ou_sender',
  chatId: 'oc_chat',
  userPid: 'bound-user',
  conversationPid: 'isolated-conversation',
};
const values = {
  appId: 'cli_app',
  appSecret: 'fixture-app-secret',
  verificationToken: 'fixture-token',
  encryptKey: 'fixture-encryption-key',
  botOpenId: 'ou_bot',
  agentCode: 'project_query',
  bindings: JSON.stringify([binding]),
};
const config: CloudConfig = {
  pid: 'own-config',
  configLevel: 'tenant',
  serviceType: 'im',
  providerCode: 'feishu_bot',
  config: JSON.stringify({ ...values, bindings: [binding] }),
  enabled: false,
  priority: 0,
};

beforeEach(() => {
  vi.clearAllMocks();
  http.get.mockResolvedValue({ code: '0', data: [config] });
  http.post.mockResolvedValue({ code: '0' });
});

describe('Feishu application bot configuration contract', () => {
  it('distinguishes the two transports and marks callback credentials sensitive', () => {
    expect(PROVIDERS_BY_TYPE.im).toContain('feishu_bot');
    expect(cloudProviderLabel('lark', 'zh-CN')).toContain('单向通知');
    expect(cloudProviderLabel('feishu_bot', 'en')).toContain('read-only');
    expect(
      PROVIDER_FIELDS.feishu_bot.filter((f) => f.sensitive).map((f) => f.key),
    ).toEqual(['appSecret', 'verificationToken', 'encryptKey']);
    expect(PROVIDER_FIELDS.lark.map((f) => f.key)).toContain('webhookUrl');
    expect(PROVIDER_FIELDS.feishu_bot.map((f) => f.key)).not.toContain(
      'webhookUrl',
    );
  });
  it('serializes a real bindings array and reopens it as editable JSON', () => {
    const persisted = serializeProviderConfig('feishu_bot', values);
    expect(persisted.bindings).toEqual([binding]);
    expect(Array.isArray(persisted.bindings)).toBe(true);
    expect(providerFormValues(JSON.stringify(persisted)).bindings).toBe(
      JSON.stringify([binding]),
    );
    expect(serializeProviderConfig('lark', { webhookUrl: 'fixture' })).toEqual({
      webhookUrl: 'fixture',
    });
  });
  it.each([
    '{}',
    '[]',
    '[{}]',
    '"[]"',
    '["not-an-object"]',
    '[{"openId":123}]',
    'not-json',
  ])('rejects invalid bindings %s', (bindings) => {
    expect(() =>
      serializeProviderConfig('feishu_bot', { ...values, bindings }),
    ).toThrow();
  });
  it('rejects ambiguous sender/chat mappings and reused conversations', () => {
    for (const next of [
      { ...binding, conversationPid: 'another' },
      { ...binding, openId: 'another-sender' },
    ]) {
      expect(() =>
        serializeProviderConfig('feishu_bot', {
          ...values,
          bindings: JSON.stringify([binding, next]),
        }),
      ).toThrow();
    }
    expect(() =>
      serializeProviderConfig('feishu_bot', { ...values, appId: '' }),
    ).toThrow();
  });
});

describe('existing configuration form', () => {
  it('selects tenant scope, starts disabled and conceals all callback secrets', () => {
    render(
      <ConfigEditorModal
        config={null}
        currentLevel="platform"
        currentServiceType="im"
        onClose={vi.fn()}
        onSave={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByTestId('cloud-config-provider'), {
      target: { value: 'feishu_bot' },
    });
    expect(screen.getByRole('radio', { name: '租户级' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '平台级' })).toBeDisabled();
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'false');
    for (const field of ['appSecret', 'verificationToken', 'encryptKey'])
      expect(screen.getByTestId(`cloud-config-field-${field}`)).toHaveAttribute(
        'type',
        'password',
      );
    expect(screen.getByTestId('cloud-config-im-guidance')).toHaveTextContent(
      'Webhook 仅发送通知',
    );
  });
  it('renders structured bindings on edit and blocks malformed arrays before save', async () => {
    const save = vi.fn();
    render(
      <ConfigEditorModal
        config={config}
        currentLevel="tenant"
        currentServiceType="im"
        onClose={vi.fn()}
        onSave={save}
      />,
    );
    expect(screen.getByTestId('cloud-config-field-bindings')).toHaveValue(
      JSON.stringify([binding]),
    );
    fireEvent.change(screen.getByTestId('cloud-config-field-bindings'), {
      target: { value: '{}' },
    });
    fireEvent.click(screen.getByTestId('cloud-config-save-btn'));
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('非空 JSON 数组'),
    );
    expect(save).not.toHaveBeenCalled();
  });
  it('submits a valid bot configuration through the existing form', async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    render(
      <ConfigEditorModal config={config} currentLevel="tenant" currentServiceType="im"
        onClose={vi.fn()} onSave={save} />,
    );
    fireEvent.click(screen.getByTestId('cloud-config-save-btn'));
    await waitFor(() => expect(save).toHaveBeenCalledWith({
      configLevel: 'tenant', serviceType: 'im', providerCode: 'feishu_bot',
      config: values, enabled: false, priority: 0,
    }));
    expect(screen.queryByTestId('cloud-config-validation-error')).not.toBeInTheDocument();
  });
  it('renders the existing card without exposing credentials or enabling a fake connection test', () => {
    render(
      <ConfigCard
        config={{ ...config, enabled: true }}
        testing={false}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        onToggle={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTitle('展开详情'));
    expect(screen.queryByText(values.appSecret)).not.toBeInTheDocument();
    expect(
      screen.queryByText(values.verificationToken),
    ).not.toBeInTheDocument();
    expect(screen.getByText(JSON.stringify([binding]))).toBeVisible();
    expect(screen.getByTestId('cloud-config-test-feishu_bot')).toBeDisabled();
  });
});

describe('configuration API payload', () => {
  it('persists bindings as an array inside config JSON, never a quoted JSON string', async () => {
    const hook = renderHook(() => useCloudConfigs({ initialLevel: 'tenant' }));
    await waitFor(() => expect(hook.result.current.loading).toBe(false));
    await act(async () => {
      expect(
        await hook.result.current.handleSave({
          configLevel: 'tenant',
          serviceType: 'im',
          providerCode: 'feishu_bot',
          config: values,
          enabled: false,
          priority: 0,
        }),
      ).toBe(true);
    });
    const body = http.post.mock.calls[0][1];
    expect(body.configLevel).toBe('tenant');
    expect(body.providerCode).toBe('feishu_bot');
    expect(JSON.parse(body.config).bindings).toEqual([binding]);
    expect(Array.isArray(JSON.parse(body.config).bindings)).toBe(true);
  });
  it('rejects platform scope and malformed bindings before any POST', async () => {
    const hook = renderHook(() => useCloudConfigs({ initialLevel: 'tenant' }));
    await waitFor(() => expect(hook.result.current.loading).toBe(false));
    await act(async () => {
      expect(
        await hook.result.current.handleSave({
          configLevel: 'platform',
          serviceType: 'im',
          providerCode: 'feishu_bot',
          config: values,
          enabled: false,
          priority: 0,
        }),
      ).toBe(false);
      expect(
        await hook.result.current.handleSave({
          configLevel: 'tenant',
          serviceType: 'im',
          providerCode: 'feishu_bot',
          config: { ...values, bindings: '{}' },
          enabled: false,
          priority: 0,
        }),
      ).toBe(false);
    });
    expect(http.post).not.toHaveBeenCalled();
  });
  it('does not report generic provider-test success for an unverified application callback', async () => {
    const hook = renderHook(() => useCloudConfigs({ initialLevel: 'tenant' }));
    await waitFor(() => expect(hook.result.current.loading).toBe(false));
    await act(async () => {
      await hook.result.current.handleTest({ ...config, enabled: true });
    });
    expect(http.post).not.toHaveBeenCalled();
    expect(http.success).not.toHaveBeenCalled();
    expect(http.error).toHaveBeenCalledWith(
      expect.stringContaining('真实签名事件回调'),
    );
  });
});
