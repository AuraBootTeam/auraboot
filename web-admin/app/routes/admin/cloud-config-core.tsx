/**
 * Cloud Config Core — shared types, constants, components, and hooks
 *
 * Extracted from cloud-config.tsx so that AuraBot provider/prompt pages
 * can reuse the same building blocks.
 */

import { useState, useEffect, useCallback } from 'react';
import {
  PencilIcon,
  TrashIcon,
  BeakerIcon,
  XMarkIcon,
  EyeIcon,
  EyeSlashIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import { get, post, del } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type ServiceType =
  | 'sms'
  | 'email'
  | 'oauth'
  | 'storage'
  | 'cdn'
  | 'im'
  | 'llm'
  | 'prompt_template';
export type ConfigLevel = 'platform' | 'tenant';

export interface CloudConfig {
  pid: string;
  configLevel: ConfigLevel;
  serviceType: ServiceType;
  providerCode: string;
  config: string; // JSON string
  enabled: boolean;
  priority: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface ProviderFieldDef {
  key: string;
  label: string;
  sensitive: boolean;
  required: boolean;
  placeholder?: string;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

export const SERVICE_TYPES: { key: ServiceType; label: string }[] = [
  { key: 'sms', label: '短信服务' },
  { key: 'email', label: '邮件服务' },
  { key: 'oauth', label: 'OAuth 登录' },
  { key: 'storage', label: '对象存储' },
  { key: 'cdn', label: 'CDN 加速' },
  { key: 'im', label: 'IM Webhook' },
  { key: 'llm', label: 'AI 模型供应商' },
  { key: 'prompt_template', label: 'Prompt 模板' },
];

export const PROVIDER_LABELS: Record<string, string> = {
  tencent_sms: '腾讯云短信',
  aliyun_sms: '阿里云短信',
  aws_sns: 'AWS SNS',
  smtp: 'SMTP 邮件',
  wechat_web: '微信网页登录',
  wechat_mp: '微信公众号登录',
  wechat_miniapp: '微信小程序登录',
  google: 'Google 登录',
  apple: 'Apple 登录',
  local: '本地存储',
  s3: 'AWS S3',
  oss: '阿里云 OSS',
  minio: 'MinIO',
  cloudfront: 'AWS CloudFront',
  aliyun_cdn: '阿里云 CDN',
  lark: 'Lark (飞书)',
  dingtalk: 'DingTalk (钉钉)',
  wecom: 'WeCom (企业微信)',
  // LLM providers
  anthropic: 'Anthropic (Claude)',
  openai: 'OpenAI',
  deepseek: 'DeepSeek',
  minimaxi: 'MiniMaxi (海螺AI)',
  qianwen: '通义千问 (Qwen)',
  zhipu: '智谱 (Zhipu)',
  moonshot: '月之暗面 (Moonshot)',
  // Prompt templates
  aurabot_system: 'AuraBot System Prompt',
  aurabot_context: 'AuraBot Context Template',
  aurabot_tool_hint: 'AuraBot Tool Hint',
};

export const PROVIDERS_BY_TYPE: Record<ServiceType, string[]> = {
  sms: ['tencent_sms', 'aliyun_sms', 'aws_sns'],
  email: ['smtp'],
  oauth: ['wechat_web', 'wechat_mp', 'wechat_miniapp', 'google', 'apple'],
  storage: ['local', 's3', 'oss', 'minio'],
  cdn: ['cloudfront', 'aliyun_cdn'],
  im: ['lark', 'dingtalk', 'wecom'],
  llm: ['anthropic', 'openai', 'deepseek', 'minimaxi', 'qianwen', 'zhipu', 'moonshot'],
  prompt_template: ['aurabot_system', 'aurabot_context', 'aurabot_tool_hint'],
};

// LLM provider fields (shared structure, different defaults)
export const llmFields = (defaults: {
  placeholder: string;
  baseUrl: string;
  model: string;
}): ProviderFieldDef[] => [
  {
    key: 'apiKey',
    label: 'API Key',
    sensitive: true,
    required: true,
    placeholder: defaults.placeholder,
  },
  {
    key: 'baseUrl',
    label: 'Base URL',
    sensitive: false,
    required: false,
    placeholder: defaults.baseUrl,
  },
  {
    key: 'defaultModel',
    label: 'Default Model',
    sensitive: false,
    required: false,
    placeholder: defaults.model,
  },
  { key: 'maxTokens', label: 'Max Tokens', sensitive: false, required: false, placeholder: '4096' },
  { key: 'displayName', label: 'Display Name', sensitive: false, required: false },
  {
    key: 'apiFormat',
    label: 'API Format (messages/chat_completions)',
    sensitive: false,
    required: false,
    placeholder: 'chat_completions',
  },
];

export const PROVIDER_FIELDS: Record<string, ProviderFieldDef[]> = {
  tencent_sms: [
    { key: 'secretId', label: 'Secret ID', sensitive: true, required: true },
    { key: 'secretKey', label: 'Secret Key', sensitive: true, required: true },
    { key: 'appId', label: 'App ID', sensitive: false, required: true },
    { key: 'signName', label: '签名名称', sensitive: false, required: true },
    {
      key: 'region',
      label: '地域',
      sensitive: false,
      required: false,
      placeholder: 'ap-guangzhou',
    },
  ],
  aliyun_sms: [
    { key: 'accessKeyId', label: 'Access Key ID', sensitive: true, required: true },
    { key: 'accessKeySecret', label: 'Access Key Secret', sensitive: true, required: true },
    { key: 'signName', label: '签名名称', sensitive: false, required: true },
    { key: 'region', label: '地域', sensitive: false, required: false, placeholder: 'cn-hangzhou' },
  ],
  aws_sns: [
    { key: 'accessKey', label: 'Access Key', sensitive: true, required: true },
    { key: 'secretKey', label: 'Secret Key', sensitive: true, required: true },
    { key: 'region', label: 'Region', sensitive: false, required: true, placeholder: 'us-east-1' },
    { key: 'topicArn', label: 'Topic ARN', sensitive: false, required: false },
  ],
  smtp: [
    {
      key: 'host',
      label: 'SMTP 服务器',
      sensitive: false,
      required: true,
      placeholder: 'smtp.example.com',
    },
    { key: 'port', label: '端口', sensitive: false, required: true, placeholder: '465' },
    { key: 'username', label: '用户名', sensitive: false, required: true },
    { key: 'password', label: '密码', sensitive: true, required: true },
    { key: 'fromAddress', label: '发件人地址', sensitive: false, required: true },
    { key: 'fromName', label: '发件人名称', sensitive: false, required: false },
    { key: 'ssl', label: '启用 SSL', sensitive: false, required: false, placeholder: 'true' },
  ],
  wechat_web: [
    { key: 'appId', label: 'App ID', sensitive: false, required: true },
    { key: 'appSecret', label: 'App Secret', sensitive: true, required: true },
    { key: 'redirectUri', label: '回调地址', sensitive: false, required: false },
  ],
  wechat_mp: [
    { key: 'appId', label: 'App ID', sensitive: false, required: true },
    { key: 'appSecret', label: 'App Secret', sensitive: true, required: true },
  ],
  wechat_miniapp: [
    { key: 'appId', label: 'App ID', sensitive: false, required: true },
    { key: 'appSecret', label: 'App Secret', sensitive: true, required: true },
  ],
  google: [
    { key: 'clientId', label: 'Client ID', sensitive: false, required: true },
    { key: 'clientSecret', label: 'Client Secret', sensitive: true, required: true },
    {
      key: 'scopes',
      label: '权限范围 (逗号分隔)',
      sensitive: false,
      required: false,
      placeholder: 'openid,email,profile',
    },
  ],
  apple: [
    { key: 'clientId', label: 'Client ID (Service ID)', sensitive: false, required: true },
    { key: 'teamId', label: 'Team ID', sensitive: false, required: true },
    { key: 'keyId', label: 'Key ID', sensitive: false, required: true },
    { key: 'privateKey', label: 'Private Key (P8)', sensitive: true, required: true },
    { key: 'redirectUri', label: '回调地址', sensitive: false, required: false },
  ],
  local: [
    {
      key: 'basePath',
      label: '存储根目录',
      sensitive: false,
      required: true,
      placeholder: '/data/uploads',
    },
    {
      key: 'baseUrl',
      label: '访问 URL 前缀',
      sensitive: false,
      required: false,
      placeholder: '/files',
    },
  ],
  s3: [
    { key: 'accessKey', label: 'Access Key', sensitive: true, required: true },
    { key: 'secretKey', label: 'Secret Key', sensitive: true, required: true },
    { key: 'bucket', label: 'Bucket', sensitive: false, required: true },
    { key: 'region', label: 'Region', sensitive: false, required: true, placeholder: 'us-east-1' },
    { key: 'endpoint', label: '自定义端点', sensitive: false, required: false },
  ],
  oss: [
    { key: 'accessKeyId', label: 'Access Key ID', sensitive: true, required: true },
    { key: 'accessKeySecret', label: 'Access Key Secret', sensitive: true, required: true },
    { key: 'bucket', label: 'Bucket', sensitive: false, required: true },
    {
      key: 'endpoint',
      label: 'Endpoint',
      sensitive: false,
      required: true,
      placeholder: 'oss-cn-hangzhou.aliyuncs.com',
    },
  ],
  minio: [
    {
      key: 'endpoint',
      label: 'Endpoint',
      sensitive: false,
      required: true,
      placeholder: 'http://localhost:9000',
    },
    { key: 'accessKey', label: 'Access Key', sensitive: true, required: true },
    { key: 'secretKey', label: 'Secret Key', sensitive: true, required: true },
    { key: 'bucket', label: 'Bucket', sensitive: false, required: true },
  ],
  cloudfront: [
    { key: 'distributionId', label: 'Distribution ID', sensitive: false, required: true },
    { key: 'domainName', label: '域名', sensitive: false, required: true },
    { key: 'accessKey', label: 'Access Key', sensitive: true, required: true },
    { key: 'secretKey', label: 'Secret Key', sensitive: true, required: true },
    { key: 'keyPairId', label: 'Key Pair ID', sensitive: false, required: false },
    { key: 'privateKey', label: 'Private Key', sensitive: true, required: false },
  ],
  aliyun_cdn: [
    { key: 'accessKeyId', label: 'Access Key ID', sensitive: true, required: true },
    { key: 'accessKeySecret', label: 'Access Key Secret', sensitive: true, required: true },
    { key: 'domainName', label: '加速域名', sensitive: false, required: true },
  ],
  lark: [
    {
      key: 'webhookUrl',
      label: 'Webhook URL',
      sensitive: true,
      required: true,
      placeholder: 'https://open.feishu.cn/open-apis/bot/v2/hook/...',
    },
    {
      key: 'secret',
      label: 'Sign Secret',
      sensitive: true,
      required: false,
      placeholder: 'Optional signing secret',
    },
  ],
  dingtalk: [
    {
      key: 'webhookUrl',
      label: 'Webhook URL',
      sensitive: true,
      required: true,
      placeholder: 'https://oapi.dingtalk.com/robot/send?access_token=...',
    },
    {
      key: 'secret',
      label: 'HMAC Secret',
      sensitive: true,
      required: false,
      placeholder: 'Optional HMAC-SHA256 secret',
    },
  ],
  wecom: [
    {
      key: 'webhookUrl',
      label: 'Webhook URL',
      sensitive: true,
      required: true,
      placeholder: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...',
    },
  ],
  // LLM providers
  anthropic: llmFields({
    placeholder: 'sk-ant-...',
    baseUrl: 'https://api.anthropic.com',
    model: 'claude-sonnet-4-6',
  }),
  openai: llmFields({ placeholder: 'sk-...', baseUrl: 'https://api.openai.com', model: 'gpt-4o' }),
  deepseek: llmFields({
    placeholder: 'sk-...',
    baseUrl: 'https://api.deepseek.com',
    model: 'deepseek-chat',
  }),
  minimaxi: llmFields({
    placeholder: 'eyJ...',
    baseUrl: 'https://api.minimaxi.chat/v1',
    model: 'MiniMax-Text-01',
  }),
  qianwen: llmFields({
    placeholder: 'sk-...',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode',
    model: 'qwen-plus',
  }),
  zhipu: llmFields({
    placeholder: '...',
    baseUrl: 'https://open.bigmodel.cn/api/paas',
    model: 'glm-4',
  }),
  moonshot: llmFields({
    placeholder: 'sk-...',
    baseUrl: 'https://api.moonshot.cn',
    model: 'moonshot-v1-8k',
  }),
  // Prompt templates
  aurabot_system: [
    {
      key: 'template',
      label: 'Template Content',
      sensitive: false,
      required: true,
      placeholder: 'You are AuraBot...',
    },
    {
      key: 'description',
      label: 'Description',
      sensitive: false,
      required: false,
      placeholder: 'Main system prompt',
    },
  ],
  aurabot_context: [
    {
      key: 'template',
      label: 'Template Content',
      sensitive: false,
      required: true,
      placeholder: 'Page type: {{pageType}}...',
    },
    {
      key: 'description',
      label: 'Description',
      sensitive: false,
      required: false,
      placeholder: 'Context injection section',
    },
  ],
  aurabot_tool_hint: [
    {
      key: 'template',
      label: 'Template Content',
      sensitive: false,
      required: true,
      placeholder: 'When the user asks...',
    },
    {
      key: 'description',
      label: 'Description',
      sensitive: false,
      required: false,
      placeholder: 'Tool calling instructions',
    },
  ],
};

// ---------------------------------------------------------------------------
// Utility functions
// ---------------------------------------------------------------------------

export function maskValue(value: string): string {
  if (!value || value.length <= 4) return '****';
  return value.slice(0, 2) + '*'.repeat(Math.min(value.length - 4, 12)) + value.slice(-2);
}

export function safeParseJSON(str: string): Record<string, string> {
  try {
    return JSON.parse(str) || {};
  } catch {
    return {};
  }
}

// ---------------------------------------------------------------------------
// useCloudConfigs hook
// ---------------------------------------------------------------------------

export interface UseCloudConfigsReturn {
  configs: CloudConfig[];
  loading: boolean;
  level: ConfigLevel;
  setLevel: (lv: ConfigLevel) => void;
  showEditor: boolean;
  setShowEditor: (v: boolean) => void;
  editingConfig: CloudConfig | null;
  testingPid: string | null;
  loadConfigs: () => Promise<void>;
  handleCreate: () => void;
  handleEdit: (config: CloudConfig) => void;
  handleDelete: (config: CloudConfig) => Promise<void>;
  handleTest: (config: CloudConfig) => Promise<void>;
  handleToggleEnabled: (config: CloudConfig) => Promise<void>;
  handleSave: (data: {
    configLevel: ConfigLevel;
    serviceType: ServiceType;
    providerCode: string;
    config: Record<string, string>;
    enabled: boolean;
    priority: number;
  }) => Promise<void>;
}

export function useCloudConfigs(): UseCloudConfigsReturn {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [configs, setConfigs] = useState<CloudConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [level, setLevel] = useState<ConfigLevel>('platform');
  const [showEditor, setShowEditor] = useState(false);
  const [editingConfig, setEditingConfig] = useState<CloudConfig | null>(null);
  const [testingPid, setTestingPid] = useState<string | null>(null);

  const loadConfigs = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<CloudConfig[]>('/api/admin/cloud-config', { level });
      if (ResultHelper.isSuccess(result) && result.data) {
        setConfigs(
          result.data.map((item) => ({
            ...item,
            serviceType: String(item.serviceType).toLowerCase() as ServiceType,
            configLevel: String(item.configLevel).toLowerCase() as ConfigLevel,
          })),
        );
      } else {
        showErrorToast(result.desc || '加载配置失败');
      }
    } catch (e: any) {
      showErrorToast(e.message || '加载配置失败');
    } finally {
      setLoading(false);
    }
  }, [level, showErrorToast]);

  useEffect(() => {
    loadConfigs();
  }, [loadConfigs]);

  const handleCreate = () => {
    setEditingConfig(null);
    setShowEditor(true);
  };

  const handleEdit = (config: CloudConfig) => {
    setEditingConfig(config);
    setShowEditor(true);
  };

  const handleDelete = async (config: CloudConfig) => {
    const providerLabel = PROVIDER_LABELS[config.providerCode] || config.providerCode;
    if (!window.confirm(`确定要删除「${providerLabel}」的配置吗?`)) return;
    try {
      const result = await del('/api/admin/cloud-config/{pid}', { pid: config.pid });
      if (ResultHelper.isSuccess(result)) {
        showSuccessToast('配置已删除');
        loadConfigs();
      } else {
        showErrorToast(result.desc || '删除失败');
      }
    } catch (e: any) {
      showErrorToast(e.message || '删除失败');
    }
  };

  const handleTest = async (config: CloudConfig) => {
    setTestingPid(config.pid);
    try {
      const result = await post('/api/admin/cloud-config/{pid}/test', { pid: config.pid });
      if (ResultHelper.isSuccess(result)) {
        showSuccessToast('连接测试成功');
      } else {
        showErrorToast(result.desc || '连接测试失败');
      }
    } catch (e: any) {
      showErrorToast(e.message || '连接测试失败');
    } finally {
      setTestingPid(null);
    }
  };

  const handleToggleEnabled = async (config: CloudConfig) => {
    try {
      const parsed = safeParseJSON(config.config);
      const result = await post('/api/admin/cloud-config', {
        ...config,
        configLevel: config.configLevel,
        serviceType: config.serviceType,
        config: JSON.stringify(parsed),
        enabled: !config.enabled,
      });
      if (ResultHelper.isSuccess(result)) {
        showSuccessToast(config.enabled ? '已禁用' : '已启用');
        loadConfigs();
      } else {
        showErrorToast(result.desc || '操作失败');
      }
    } catch (e: any) {
      showErrorToast(e.message || '操作失败');
    }
  };

  const handleSave = async (data: {
    configLevel: ConfigLevel;
    serviceType: ServiceType;
    providerCode: string;
    config: Record<string, string>;
    enabled: boolean;
    priority: number;
  }) => {
    try {
      const body: any = {
        configLevel: data.configLevel,
        serviceType: data.serviceType,
        providerCode: data.providerCode,
        config: JSON.stringify(data.config),
        enabled: data.enabled,
        priority: data.priority,
      };
      if (editingConfig) {
        body.pid = editingConfig.pid;
      }
      const result = await post('/api/admin/cloud-config', body);
      if (ResultHelper.isSuccess(result)) {
        showSuccessToast(editingConfig ? '配置已更新' : '配置已创建');
        setShowEditor(false);
        loadConfigs();
      } else {
        showErrorToast(result.desc || '保存失败');
      }
    } catch (e: any) {
      showErrorToast(e.message || '保存失败');
    }
  };

  return {
    configs,
    loading,
    level,
    setLevel,
    showEditor,
    setShowEditor,
    editingConfig,
    testingPid,
    loadConfigs,
    handleCreate,
    handleEdit,
    handleDelete,
    handleTest,
    handleToggleEnabled,
    handleSave,
  };
}

// ---------------------------------------------------------------------------
// ConfigCard component
// ---------------------------------------------------------------------------

export function ConfigCard({
  config,
  testing,
  onEdit,
  onDelete,
  onTest,
  onToggle,
}: {
  config: CloudConfig;
  testing: boolean;
  onEdit: () => void;
  onDelete: () => void;
  onTest: () => void;
  onToggle: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const parsed = safeParseJSON(config.config);
  const fields = PROVIDER_FIELDS[config.providerCode] || [];

  return (
    <div
      className={`rounded-lg border transition-colors ${
        config.enabled
          ? 'border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800'
          : 'border-gray-100 bg-gray-50 dark:border-gray-700/50 dark:bg-gray-800/50'
      }`}
      data-testid={`cloud-config-card-${config.providerCode}`}
    >
      <div className="flex items-center justify-between px-4 py-3">
        <div className="flex items-center gap-3">
          {/* Enable toggle */}
          <button
            onClick={onToggle}
            className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 focus:outline-none ${
              config.enabled ? 'bg-blue-600' : 'bg-gray-300 dark:bg-gray-600'
            }`}
            role="switch"
            aria-checked={config.enabled}
            data-testid={`cloud-config-toggle-${config.providerCode}`}
          >
            <span
              className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white shadow transition-transform ${
                config.enabled ? 'translate-x-4' : 'translate-x-0.5'
              }`}
            />
          </button>

          <div>
            <span className="text-sm font-medium text-gray-900 dark:text-white">
              {PROVIDER_LABELS[config.providerCode] || config.providerCode}
            </span>
            <span className="ml-2 text-xs text-gray-400 dark:text-gray-500">
              {config.providerCode}
            </span>
            {config.priority > 0 && (
              <span className="ml-2 inline-flex items-center rounded bg-blue-50 px-1.5 py-0.5 text-[10px] font-medium text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
                P{config.priority}
              </span>
            )}
          </div>
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={() => setExpanded(!expanded)}
            className="rounded p-1.5 text-gray-400 transition-colors hover:text-gray-600 dark:hover:text-gray-300"
            title={expanded ? '收起详情' : '展开详情'}
          >
            {expanded ? <EyeSlashIcon className="h-4 w-4" /> : <EyeIcon className="h-4 w-4" />}
          </button>
          <button
            onClick={onTest}
            disabled={testing || !config.enabled}
            className="rounded p-1.5 text-gray-400 transition-colors hover:text-green-600 disabled:cursor-not-allowed disabled:opacity-40 dark:hover:text-green-400"
            title="测试连接"
            data-testid={`cloud-config-test-${config.providerCode}`}
          >
            {testing ? (
              <div className="h-4 w-4 animate-spin rounded-full border-b-2 border-green-600" />
            ) : (
              <BeakerIcon className="h-4 w-4" />
            )}
          </button>
          <button
            onClick={onEdit}
            className="rounded p-1.5 text-gray-400 transition-colors hover:text-blue-600 dark:hover:text-blue-400"
            title="编辑"
            data-testid={`cloud-config-edit-${config.providerCode}`}
          >
            <PencilIcon className="h-4 w-4" />
          </button>
          <button
            onClick={onDelete}
            className="rounded p-1.5 text-gray-400 transition-colors hover:text-red-600 dark:hover:text-red-400"
            title="删除"
            data-testid={`cloud-config-delete-${config.providerCode}`}
          >
            <TrashIcon className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Expanded detail */}
      {expanded && fields.length > 0 && (
        <div className="border-t border-gray-100 px-4 pt-3 pb-3 dark:border-gray-700">
          <div className="grid grid-cols-1 gap-x-6 gap-y-1.5 sm:grid-cols-2">
            {fields.map((f) => {
              const rawVal = parsed[f.key] || '';
              const display = f.sensitive && rawVal ? maskValue(rawVal) : rawVal || '-';
              return (
                <div key={f.key} className="flex items-baseline gap-2 text-sm">
                  <span className="w-32 shrink-0 text-right text-gray-500 dark:text-gray-400">
                    {f.label}:
                  </span>
                  <span className="font-mono text-xs break-all text-gray-800 dark:text-gray-200">
                    {display}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// ConfigEditorModal component
// ---------------------------------------------------------------------------

export function ConfigEditorModal({
  config,
  currentLevel,
  currentServiceType,
  serviceTypes,
  onClose,
  onSave,
}: {
  config: CloudConfig | null;
  currentLevel: ConfigLevel;
  currentServiceType: ServiceType;
  /** Which service types to show in the dropdown. Defaults to all SERVICE_TYPES. */
  serviceTypes?: { key: ServiceType; label: string }[];
  onClose: () => void;
  onSave: (data: {
    configLevel: ConfigLevel;
    serviceType: ServiceType;
    providerCode: string;
    config: Record<string, string>;
    enabled: boolean;
    priority: number;
  }) => void;
}) {
  const isEdit = !!config;
  const visibleServiceTypes = serviceTypes || SERVICE_TYPES;

  const [configLevel, setConfigLevel] = useState<ConfigLevel>(config?.configLevel || currentLevel);
  const [serviceType, setServiceType] = useState<ServiceType>(
    config?.serviceType || currentServiceType,
  );
  const [providerCode, setProviderCode] = useState(config?.providerCode || '');
  const [configValues, setConfigValues] = useState<Record<string, string>>(
    config ? safeParseJSON(config.config) : {},
  );
  const [enabled, setEnabled] = useState(config?.enabled ?? true);
  const [priority, setPriority] = useState(config?.priority ?? 0);
  const [saving, setSaving] = useState(false);
  const [showSensitive, setShowSensitive] = useState<Record<string, boolean>>({});

  // When provider changes, reset config values (but keep if editing)
  const handleProviderChange = (code: string) => {
    setProviderCode(code);
    if (!isEdit) {
      setConfigValues({});
    }
  };

  // When service type changes, reset provider
  const handleServiceTypeChange = (st: ServiceType) => {
    setServiceType(st);
    if (!isEdit) {
      setProviderCode('');
      setConfigValues({});
    }
  };

  const fields = PROVIDER_FIELDS[providerCode] || [];
  const availableProviders = PROVIDERS_BY_TYPE[serviceType] || [];

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // Validate required fields
    for (const f of fields) {
      if (f.required && !configValues[f.key]?.trim()) {
        return;
      }
    }

    setSaving(true);
    try {
      await onSave({
        configLevel,
        serviceType,
        providerCode,
        config: configValues,
        enabled,
        priority,
      });
    } finally {
      setSaving(false);
    }
  };

  const toggleSensitiveVisibility = (key: string) => {
    setShowSensitive((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/50 pt-[10vh]">
      <div className="mx-4 mb-8 w-full max-w-lg rounded-xl bg-white shadow-2xl dark:bg-gray-800">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
            {isEdit ? '编辑配置' : '新建云服务配置'}
          </h3>
          <button
            onClick={onClose}
            className="rounded p-1 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
          >
            <XMarkIcon className="h-5 w-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4 p-6">
          {/* Level */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              配置级别
            </label>
            <div className="flex gap-3">
              {(['platform', 'tenant'] as ConfigLevel[]).map((lv) => (
                <label key={lv} className="flex cursor-pointer items-center gap-2">
                  <input
                    type="radio"
                    name="configLevel"
                    value={lv}
                    checked={configLevel === lv}
                    onChange={() => setConfigLevel(lv)}
                    className="text-blue-600 focus:ring-blue-500"
                    disabled={isEdit}
                  />
                  <span className="text-sm text-gray-700 dark:text-gray-300">
                    {lv === 'platform' ? '平台级' : '租户级'}
                  </span>
                </label>
              ))}
            </div>
          </div>

          {/* Service type */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              服务类型 <span className="text-red-500">*</span>
            </label>
            <select
              value={serviceType}
              onChange={(e) => handleServiceTypeChange(e.target.value as ServiceType)}
              disabled={isEdit}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-blue-500 disabled:opacity-60 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
            >
              {visibleServiceTypes.map((st) => (
                <option key={st.key} value={st.key}>
                  {st.label}
                </option>
              ))}
            </select>
          </div>

          {/* Provider */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              服务商 <span className="text-red-500">*</span>
            </label>
            <select
              value={providerCode}
              onChange={(e) => handleProviderChange(e.target.value)}
              disabled={isEdit}
              required
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-blue-500 disabled:opacity-60 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
            >
              <option value="">-- 请选择 --</option>
              {availableProviders.map((code) => (
                <option key={code} value={code}>
                  {PROVIDER_LABELS[code] || code}
                </option>
              ))}
            </select>
          </div>

          {/* Dynamic config fields */}
          {fields.length > 0 && (
            <div className="space-y-3 pt-2">
              <div className="text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                配置参数
              </div>
              {fields.map((f) => (
                <div key={f.key}>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    {f.label}
                    {f.required && <span className="ml-0.5 text-red-500">*</span>}
                    {f.sensitive && (
                      <span className="ml-1 text-[10px] font-normal text-amber-600 dark:text-amber-400">
                        (敏感)
                      </span>
                    )}
                  </label>
                  <div className="relative">
                    {f.key === 'template' ? (
                      <textarea
                        rows={10}
                        value={configValues[f.key] || ''}
                        onChange={(e) =>
                          setConfigValues((prev) => ({
                            ...prev,
                            [f.key]: e.target.value,
                          }))
                        }
                        required={f.required}
                        placeholder={f.placeholder || ''}
                        className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 font-mono text-sm text-gray-900 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
                      />
                    ) : (
                      <input
                        type={f.sensitive && !showSensitive[f.key] ? 'password' : 'text'}
                        value={configValues[f.key] || ''}
                        onChange={(e) =>
                          setConfigValues((prev) => ({
                            ...prev,
                            [f.key]: e.target.value,
                          }))
                        }
                        required={f.required}
                        placeholder={f.placeholder || ''}
                        className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 pr-9 text-sm text-gray-900 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                        autoComplete="off"
                      />
                    )}
                    {f.sensitive && (
                      <button
                        type="button"
                        onClick={() => toggleSensitiveVisibility(f.key)}
                        className="absolute top-1/2 right-2 -translate-y-1/2 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
                        tabIndex={-1}
                      >
                        {showSensitive[f.key] ? (
                          <EyeSlashIcon className="h-4 w-4" />
                        ) : (
                          <EyeIcon className="h-4 w-4" />
                        )}
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Priority */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              优先级
            </label>
            <input
              type="number"
              min={0}
              max={99}
              value={priority}
              onChange={(e) => setPriority(Number(e.target.value))}
              className="w-24 rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
            />
            <span className="ml-2 text-xs text-gray-400">数字越大优先级越高</span>
          </div>

          {/* Enabled */}
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setEnabled(!enabled)}
              className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 focus:outline-none ${
                enabled ? 'bg-blue-600' : 'bg-gray-300 dark:bg-gray-600'
              }`}
              role="switch"
              aria-checked={enabled}
            >
              <span
                className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white shadow transition-transform ${
                  enabled ? 'translate-x-4' : 'translate-x-0.5'
                }`}
              />
            </button>
            <span className="text-sm text-gray-700 dark:text-gray-300">
              {enabled ? '启用' : '禁用'}
            </span>
          </div>

          {/* Actions */}
          <div className="flex justify-end gap-3 border-t border-gray-200 pt-3 dark:border-gray-700">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg bg-gray-100 px-4 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={saving || !providerCode}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="cloud-config-save-btn"
            >
              {saving ? '保存中...' : isEdit ? '保存更改' : '创建'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
