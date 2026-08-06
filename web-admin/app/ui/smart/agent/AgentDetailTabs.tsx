/**
 * AI Colleague Detail — Tabbed Configuration Page
 *
 * 7 tabs: Profile, Tools & Skills, Knowledge, Memory, Releases, Run History, Schedules.
 * AuraBot (aurabot) renders in read-only mode.
 */

import { useState, useEffect, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router';
import {
  ArrowLeftIcon,
  UserCircleIcon,
  WrenchScrewdriverIcon,
  BookOpenIcon,
  CircleStackIcon,
  ClockIcon,
  CalendarDaysIcon,
  CheckIcon,
  SparklesIcon,
  ShieldCheckIcon,
  InformationCircleIcon,
  LockClosedIcon,
  UserGroupIcon,
  GlobeAltIcon,
  BuildingOfficeIcon,
  PauseCircleIcon,
  PlayCircleIcon,
  RocketLaunchIcon,
  XMarkIcon,
  ChevronDownIcon,
} from '@heroicons/react/24/outline';
import { get, post, put, del } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { useTimezone } from '~/contexts/TimezoneContext';
import { formatInTimezone } from '~/shared/services/dateTimeFormatService';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Where an agent sits in the org chart. Public identifiers only — see the endpoint's javadoc. */
interface OrgPlacement {
  enrolled: boolean;
  employeePid: string | null;
  departmentName: string | null;
  positionName: string | null;
}

interface AgentDetail {
  pid: string;
  agent_code: string;
  name: string;
  description: string | null;
  avatar_url: string | null;
  agent_type: string;
  model: string | null;
  system_prompt: string | null;
  personality: string | null;
  expertise: string | null;
  communication_style: string | null;
  boundaries: string | null;
  soul_goals: string | null;
  tools: string | null;
  skills: string | null;
  guardrails: string | null;
  status: string;
  max_tools: number;
  max_concurrent_runs: number;
  execution_timeout_seconds: number;
  allowed_models: string[] | string | null; // null or "*" = all, or ["crm_account_common","crm_lead_common"]
  allowed_operations: string[] | null; // ["query","create","update","delete","transition"]
  knowledge_base_ids: string[] | string | null;
  visibility: 'private' | 'team' | 'tenant';
  employee_id: number | null;
  system_user_id: number | null;
  created_at: string;
  updated_at: string;
}

interface DepartmentTreeNode {
  pid: string;
  name: string;
  parentPid: string | null;
  employeeCount: number;
  children: DepartmentTreeNode[];
}

interface PositionItem {
  pid: string;
  org_pos_name: string;
  org_pos_dept_id: string;
  org_pos_level: string;
  org_pos_status: string;
}

interface MetaModelItem {
  code: string;
  displayName: string | null;
  modelCategory: string | null;
}

interface MemoryItem {
  pid: string;
  memory_type: string;
  category: string | null;
  memory_title: string | null;
  memory_content: string;
  importance: number;
  created_at: string;
}

interface KnowledgeBase {
  pid: string;
  name: string;
  description: string | null;
  status: 'active' | 'disabled';
  docCount: number;
  chunkCount: number;
}

interface RunRecord {
  pid: string;
  run_status: string;
  model: string;
  started_at: string;
  completed_at: string | null;
  duration_ms: number | null;
  input_tokens: number;
  output_tokens: number;
  total_cost: number;
  task_title: string | null;
}

interface AgentReleaseItem {
  pid: string;
  release_no: number;
  release_hash: string;
  status: 'published' | 'deprecated';
  source_updated_at: string | null;
  published_at: string;
  deployed: boolean;
}

interface AgentDeploymentPolicy {
  deploymentPid: string;
  channelPolicy: {
    version?: string;
    allowedChannels?: string[];
    allowedInitiatorTypes?: string[];
    allowedUserIds?: number[];
    allowedMemberIds?: number[];
    allowedRoleIds?: number[];
  };
  policySnapshot: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const AURABOT_CODE = 'aurabot';

type TabKey = 'profile' | 'tools' | 'knowledge' | 'memory' | 'releases' | 'runs' | 'schedules';

const AGENT_TYPES = ['reactive', 'copilot', 'autonomous', 'proactive', 'workflow'];
const COMM_STYLES = ['professional', 'friendly', 'concise', 'detailed'];
const DEPLOYMENT_CHANNELS = [
  { value: 'web', labelKey: 'ai.colleagues.policy.channel.web', fallback: 'Web chat' },
  { value: 'im_group', labelKey: 'ai.colleagues.policy.channel.imGroup', fallback: 'IM group' },
  {
    value: 'schedule',
    labelKey: 'ai.colleagues.policy.channel.schedule',
    fallback: 'Scheduled task',
  },
  { value: 'event', labelKey: 'ai.colleagues.policy.channel.event', fallback: 'Event' },
  { value: 'webhook', labelKey: 'ai.colleagues.policy.channel.webhook', fallback: 'Webhook' },
  { value: 'api', labelKey: 'ai.colleagues.policy.channel.api', fallback: 'API' },
] as const;
const DEPLOYMENT_INITIATORS = [
  { value: 'human', labelKey: 'ai.colleagues.policy.initiator.human', fallback: 'Person' },
  { value: 'system', labelKey: 'ai.colleagues.policy.initiator.system', fallback: 'System' },
  {
    value: 'schedule',
    labelKey: 'ai.colleagues.policy.initiator.schedule',
    fallback: 'Scheduled task',
  },
  { value: 'event', labelKey: 'ai.colleagues.policy.initiator.event', fallback: 'Event' },
  {
    value: 'agent_handoff',
    labelKey: 'ai.colleagues.policy.initiator.agentHandoff',
    fallback: 'AI colleague handoff',
  },
] as const;

// ---------------------------------------------------------------------------
// Tab definitions
// ---------------------------------------------------------------------------

function useTabs(t: (key: string, params?: Record<string, any>, fallback?: string) => string) {
  return [
    {
      key: 'profile' as TabKey,
      label: t('ai.colleagues.tab.profile', undefined, 'Profile'),
      icon: UserCircleIcon,
    },
    {
      key: 'tools' as TabKey,
      label: t('ai.colleagues.tab.tools', undefined, 'Tools & Skills'),
      icon: WrenchScrewdriverIcon,
    },
    {
      key: 'knowledge' as TabKey,
      label: t('ai.colleagues.tab.knowledge', undefined, 'Knowledge'),
      icon: CircleStackIcon,
    },
    {
      key: 'memory' as TabKey,
      label: t('ai.colleagues.tab.memory', undefined, 'Memory'),
      icon: BookOpenIcon,
    },
    {
      key: 'releases' as TabKey,
      label: t('ai.colleagues.tab.releases', undefined, 'Releases'),
      icon: RocketLaunchIcon,
    },
    {
      key: 'runs' as TabKey,
      label: t('ai.colleagues.tab.runs', undefined, 'Run History'),
      icon: ClockIcon,
    },
    {
      key: 'schedules' as TabKey,
      label: t('ai.colleagues.tab.schedules', undefined, 'Schedules'),
      icon: CalendarDaysIcon,
    },
  ];
}

// ---------------------------------------------------------------------------
// Profile Tab (working form)
// ---------------------------------------------------------------------------

function ProfileTab({
  agent,
  readOnly,
  onSave,
  saving,
}: {
  agent: AgentDetail;
  readOnly: boolean;
  onSave: (data: Partial<AgentDetail>) => void;
  saving: boolean;
}) {
  const { t } = useI18n();
  const [form, setForm] = useState({
    name: agent.name,
    description: agent.description ?? '',
    agent_type: agent.agent_type,
    model: agent.model ?? '',
    system_prompt: agent.system_prompt ?? '',
    personality: agent.personality ?? '',
    expertise: agent.expertise ?? '',
    communication_style: agent.communication_style ?? '',
    boundaries: agent.boundaries ?? '',
    soul_goals: agent.soul_goals ?? '',
    max_concurrent_runs: agent.max_concurrent_runs,
    execution_timeout_seconds: agent.execution_timeout_seconds,
    visibility: agent.visibility ?? 'private',
  });

  const handleChange = (field: string, value: string | number) => {
    setForm((prev) => ({ ...prev, [field]: value }));
  };

  const fieldClass = readOnly
    ? 'bg-subtle dark:bg-subtle cursor-not-allowed'
    : 'bg-panel dark:bg-subtle';

  return (
    <div className="max-w-2xl space-y-6">
      {/* Basic Info */}
      <section>
        <h3 className="text-text mb-3 text-sm font-semibold tracking-wide uppercase dark:text-white">
          {t('ai.colleagues.section.basicInfo', undefined, 'Basic Information')}
        </h3>
        <div className="space-y-4">
          <div>
            <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
              {t('ai.colleagues.field.name', undefined, 'Name')} *
            </label>
            <input
              type="text"
              value={form.name}
              onChange={(e) => handleChange('name', e.target.value)}
              disabled={readOnly}
              className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
              data-testid="agent-name-input"
            />
          </div>

          <div>
            <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
              {t('ai.colleagues.field.description', undefined, 'Description')}
            </label>
            <textarea
              rows={3}
              value={form.description}
              onChange={(e) => handleChange('description', e.target.value)}
              disabled={readOnly}
              className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
              data-testid="agent-description-input"
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
                {t('ai.colleagues.field.agentType', undefined, 'Agent Type')}
              </label>
              <select
                value={form.agent_type}
                onChange={(e) => handleChange('agent_type', e.target.value)}
                disabled={readOnly}
                className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
              >
                {AGENT_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
                {t('ai.colleagues.field.model', undefined, 'Model')}
              </label>
              <input
                type="text"
                value={form.model}
                onChange={(e) => handleChange('model', e.target.value)}
                disabled={readOnly}
                className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
              />
            </div>
          </div>
        </div>
      </section>

      {/* Soul Profile */}
      <section>
        <h3 className="text-text mb-3 text-sm font-semibold tracking-wide uppercase dark:text-white">
          {t('ai.colleagues.section.soulProfile', undefined, 'Soul Profile')}
        </h3>
        <div className="space-y-4">
          <div>
            <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
              {t('ai.colleagues.field.personality', undefined, 'Personality')}
            </label>
            <textarea
              rows={2}
              value={form.personality}
              onChange={(e) => handleChange('personality', e.target.value)}
              disabled={readOnly}
              className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
            />
          </div>

          <div>
            <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
              {t('ai.colleagues.field.expertise', undefined, 'Expertise')}
            </label>
            <textarea
              rows={2}
              value={form.expertise}
              onChange={(e) => handleChange('expertise', e.target.value)}
              disabled={readOnly}
              className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
            />
          </div>

          <div>
            <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
              {t('ai.colleagues.field.communicationStyle', undefined, 'Communication Style')}
            </label>
            <select
              value={form.communication_style}
              onChange={(e) => handleChange('communication_style', e.target.value)}
              disabled={readOnly}
              className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
            >
              <option value="">
                {t('ai.colleagues.field.selectStyle', undefined, 'Select style...')}
              </option>
              {COMM_STYLES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
              {t('ai.colleagues.field.boundaries', undefined, 'Boundaries')}
            </label>
            <textarea
              rows={2}
              value={form.boundaries}
              onChange={(e) => handleChange('boundaries', e.target.value)}
              disabled={readOnly}
              className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
            />
          </div>

          <div>
            <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
              {t('ai.colleagues.field.systemPrompt', undefined, 'System Prompt')}
            </label>
            <textarea
              rows={6}
              value={form.system_prompt}
              onChange={(e) => handleChange('system_prompt', e.target.value)}
              disabled={readOnly}
              className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 font-mono text-sm dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
            />
          </div>
        </div>
      </section>

      {/* Execution Limits */}
      <section>
        <h3 className="text-text mb-3 text-sm font-semibold tracking-wide uppercase dark:text-white">
          {t('ai.colleagues.section.executionLimits', undefined, 'Execution Limits')}
        </h3>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
              {t('ai.colleagues.field.maxConcurrentRuns', undefined, 'Max Concurrent Runs')}
            </label>
            <input
              type="number"
              min={1}
              max={10}
              value={form.max_concurrent_runs}
              onChange={(e) => handleChange('max_concurrent_runs', parseInt(e.target.value) || 1)}
              disabled={readOnly}
              className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
            />
          </div>
          <div>
            <label className="text-text-2 dark:text-text-3 mb-1 block text-sm font-medium">
              {t('ai.colleagues.field.timeout', undefined, 'Timeout (seconds)')}
            </label>
            <input
              type="number"
              min={30}
              max={3600}
              value={form.execution_timeout_seconds}
              onChange={(e) =>
                handleChange('execution_timeout_seconds', parseInt(e.target.value) || 300)
              }
              disabled={readOnly}
              className={`border-border-strong text-text dark:border-border-strong w-full rounded-lg border px-3 py-2 dark:text-white ${fieldClass} focus:border-accent0 focus:ring-accent0 transition-colors focus:ring-2`}
            />
          </div>
        </div>
      </section>

      {/* Visibility / Sharing */}
      <section>
        <h3 className="text-text mb-1 text-sm font-semibold tracking-wide uppercase dark:text-white">
          {t('ai.colleagues.section.visibility', undefined, 'Visibility & Sharing')}
        </h3>
        <p className="text-text-3 dark:text-text-3 mb-3 text-sm">
          {t(
            'ai.colleagues.section.visibilityDesc',
            undefined,
            'Control who can see and use this AI colleague.',
          )}
        </p>
        {readOnly ? (
          <div className="border-accent bg-accent-weak dark:border-accent dark:bg-accent-weak/10 flex items-center gap-2 rounded-lg border px-3 py-2">
            <GlobeAltIcon className="text-accent0 h-4 w-4 flex-shrink-0" />
            <span className="text-accent dark:text-accent text-sm font-medium">
              {t(
                'ai.colleagues.visibility.tenant',
                undefined,
                'Tenant — Everyone in the organization can see and use',
              )}
            </span>
          </div>
        ) : (
          <div className="space-y-2" data-testid="visibility-selector">
            {(
              [
                {
                  value: 'private',
                  Icon: LockClosedIcon,
                  label: t('ai.colleagues.visibility.private', undefined, 'Private'),
                  desc: t(
                    'ai.colleagues.visibility.privateDesc',
                    undefined,
                    'Only you can see and use this agent',
                  ),
                  color: 'border-border dark:border-border',
                  activeColor:
                    'border-border-strong bg-subtle dark:border-border0 dark:bg-subtle/50',
                  iconColor: 'text-text-3',
                },
                {
                  value: 'team',
                  Icon: UserGroupIcon,
                  label: t('ai.colleagues.visibility.team', undefined, 'Team'),
                  desc: t(
                    'ai.colleagues.visibility.teamDesc',
                    undefined,
                    'Members of your department can see and use',
                  ),
                  color: 'border-border dark:border-border',
                  activeColor:
                    'border-accent bg-accent-weak dark:border-accent dark:bg-accent-weak/10',
                  iconColor: 'text-accent0',
                },
                {
                  value: 'tenant',
                  Icon: GlobeAltIcon,
                  label: t('ai.colleagues.visibility.tenant', undefined, 'Tenant'),
                  desc: t(
                    'ai.colleagues.visibility.tenantDesc',
                    undefined,
                    'Everyone in the organization can see and use',
                  ),
                  color: 'border-border dark:border-border',
                  activeColor:
                    'border-accent bg-accent-weak dark:border-accent dark:bg-accent-weak/10',
                  iconColor: 'text-accent0',
                },
              ] as const
            ).map(({ value, Icon, label, desc, color, activeColor, iconColor }) => {
              const selected = form.visibility === value;
              return (
                <label
                  key={value}
                  className={`flex cursor-pointer items-center gap-3 rounded-lg border p-3 transition-colors ${selected ? activeColor : color} hover:border-accent dark:hover:border-accent`}
                  data-testid={`visibility-option-${value}`}
                >
                  <input
                    type="radio"
                    name="visibility"
                    value={value}
                    checked={selected}
                    onChange={() => handleChange('visibility', value)}
                    className="border-border-strong text-accent focus:ring-accent0 h-4 w-4"
                  />
                  <Icon className={`h-4 w-4 flex-shrink-0 ${iconColor}`} />
                  <div>
                    <span className="text-text text-sm font-medium dark:text-white">{label}</span>
                    <span className="text-text-3 dark:text-text-3 block text-xs">{desc}</span>
                  </div>
                </label>
              );
            })}
          </div>
        )}
      </section>

      {/* Save button */}
      {!readOnly && (
        <div className="border-border dark:border-border border-t pt-4">
          <button
            onClick={() => onSave(form)}
            disabled={saving}
            className="bg-accent hover:bg-accent-hover inline-flex items-center gap-1.5 rounded-lg px-5 py-2.5 text-sm font-medium text-white transition-colors disabled:opacity-50"
            data-testid="agent-save-btn"
          >
            <CheckIcon className="h-4 w-4" />
            {saving
              ? t('ai.colleagues.action.saving', undefined, 'Saving...')
              : t('ai.colleagues.action.save', undefined, 'Save Changes')}
          </button>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tools & Skills Tab
// ---------------------------------------------------------------------------

interface ToolRecord {
  pid: string;
  tool_code: string;
  tool_name: string;
  tool_type: string;
}

interface SkillRecord {
  pid: string;
  skill_code: string;
  skill_name: string;
  skill_category: string | null;
  execution_mode: string | null;
}

// ---------------------------------------------------------------------------
// Model group helpers — derive group from model code prefix
// ---------------------------------------------------------------------------

const MODEL_GROUP_MAP: Record<string, string> = {
  crm: 'CRM',
  sl: 'Sales',
  org: 'Organization',
  bpm: 'BPM',
  showcase: 'Showcase',
  data_permission: 'Platform',
  webhook: 'Platform',
  api_connector: 'Platform',
  tenant_member: 'Platform',
  scheduled_task: 'Platform',
  sla: 'Platform',
};

function modelGroup(code: string): string {
  // Check full code first (e.g. "data_permission")
  if (MODEL_GROUP_MAP[code]) return MODEL_GROUP_MAP[code];
  // Check prefix before first underscore
  const prefix = code.split('_')[0];
  return MODEL_GROUP_MAP[prefix] ?? 'Other';
}

/**
 * Reads a jsonb list column as it can actually arrive. The dynamic read path
 * hands these back as a JSON string, not a parsed array, so an Array.isArray
 * check alone silently falls through to the default — which is how a cleared
 * Delete checkbox came back ticked on reload while the database held the
 * cleared value all along.
 */
function asStringList(value: unknown): string[] | null {
  if (Array.isArray(value)) return value as string[];
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed || trimmed === '*') return null;
    try {
      const parsed = JSON.parse(trimmed);
      return Array.isArray(parsed) ? (parsed as string[]) : null;
    } catch {
      return null;
    }
  }
  return null;
}

const ALL_OPERATIONS = ['query', 'create', 'update', 'delete', 'transition'] as const;

const OPERATION_LABELS: Record<string, { label: string; description: string }> = {
  query: { label: 'Query', description: 'Read and search records' },
  create: { label: 'Create', description: 'Create new records' },
  update: { label: 'Update', description: 'Modify existing records' },
  delete: { label: 'Delete', description: 'Remove records' },
  transition: { label: 'Transition', description: 'Change record status' },
};

function ToolsSkillsTab({
  agent,
  readOnly,
  onSave,
  saving,
}: {
  agent: AgentDetail;
  readOnly: boolean;
  onSave: (data: Partial<AgentDetail>) => void;
  saving: boolean;
}) {
  const { t } = useI18n();
  const [allModels, setAllModels] = useState<MetaModelItem[]>([]);
  const [loadingModels, setLoadingModels] = useState(true);
  const [tools, setTools] = useState<ToolRecord[]>([]);
  const [skills, setSkills] = useState<SkillRecord[]>([]);
  const [loadingTools, setLoadingTools] = useState(true);
  const [loadingSkills, setLoadingSkills] = useState(true);

  // Derive "all access" from allowed_models
  const isAllModelsAccess = asStringList(agent.allowed_models) === null;

  const [allAccess, setAllAccess] = useState(isAllModelsAccess);
  const [selectedModels, setSelectedModels] = useState<Set<string>>(() => {
    if (isAllModelsAccess) return new Set<string>();
    return new Set(asStringList(agent.allowed_models) ?? []);
  });
  const [selectedOps, setSelectedOps] = useState<Set<string>>(() => {
    return new Set(asStringList(agent.allowed_operations) ?? ALL_OPERATIONS);
  });
  const [dirty, setDirty] = useState(false);

  // Fetch published models
  useEffect(() => {
    (async () => {
      try {
        const res = await get<{ records: MetaModelItem[]; total: number }>('/api/meta/models', {
          page: 1,
          size: 500,
          status: 'published',
          currentOnly: true,
        });
        if (ResultHelper.isSuccess(res) && res.data?.records) {
          setAllModels(res.data.records);
        }
      } catch {
        // silent
      } finally {
        setLoadingModels(false);
      }
    })();
  }, []);

  // Fetch tools & skills (keep existing)
  useEffect(() => {
    (async () => {
      try {
        const res = await get<{ records: ToolRecord[] }>('/api/dynamic/agent-tool/list', {
          pageNum: 1,
          pageSize: 50,
        });
        if (ResultHelper.isSuccess(res) && res.data?.records) {
          setTools(res.data.records);
        }
      } catch {
        // silent
      } finally {
        setLoadingTools(false);
      }
    })();

    (async () => {
      try {
        const res = await get<{ records: SkillRecord[] }>('/api/dynamic/agent-skill/list', {
          pageNum: 1,
          pageSize: 50,
        });
        if (ResultHelper.isSuccess(res) && res.data?.records) {
          setSkills(res.data.records);
        }
      } catch {
        // silent
      } finally {
        setLoadingSkills(false);
      }
    })();
  }, []);

  // Group models by prefix
  const groupedModels = allModels.reduce<Record<string, MetaModelItem[]>>((acc, m) => {
    const group = modelGroup(m.code);
    if (!acc[group]) acc[group] = [];
    acc[group].push(m);
    return acc;
  }, {});

  const sortedGroups = Object.entries(groupedModels).sort(([a], [b]) => a.localeCompare(b));

  const toggleAllAccess = () => {
    if (readOnly) return;
    setAllAccess((prev) => !prev);
    if (!allAccess) {
      setSelectedModels(new Set());
    }
    setDirty(true);
  };

  const toggleModel = (code: string) => {
    if (readOnly || allAccess) return;
    setSelectedModels((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });
    setDirty(true);
  };

  const toggleGroupAll = (groupModels: MetaModelItem[]) => {
    if (readOnly || allAccess) return;
    const codes = groupModels.map((m) => m.code);
    const allSelected = codes.every((c) => selectedModels.has(c));
    setSelectedModels((prev) => {
      const next = new Set(prev);
      if (allSelected) {
        codes.forEach((c) => next.delete(c));
      } else {
        codes.forEach((c) => next.add(c));
      }
      return next;
    });
    setDirty(true);
  };

  const toggleOp = (op: string) => {
    if (readOnly) return;
    setSelectedOps((prev) => {
      const next = new Set(prev);
      if (next.has(op)) next.delete(op);
      else next.add(op);
      return next;
    });
    setDirty(true);
  };

  const handleSaveScope = () => {
    // null, not '*': the column is jsonb and a bare asterisk is not valid JSON,
    // so the write failed at the database with a syntax error the page never
    // showed. Null already means "no restriction" to both the reader above and
    // the policy that enforces it.
    const allowedModels = allAccess ? null : Array.from(selectedModels);
    const allowedOperations = Array.from(selectedOps);
    onSave({
      allowed_models: allowedModels,
      allowed_operations: allowedOperations,
    } as Partial<AgentDetail>);
    setDirty(false);
  };

  const skeleton = (
    <div className="space-y-2">
      {[1, 2, 3].map((i) => (
        <div key={i} className="bg-subtle dark:bg-subtle h-10 animate-pulse rounded" />
      ))}
    </div>
  );

  const isAuraBot = agent.agent_code === AURABOT_CODE;

  return (
    <div className="max-w-4xl space-y-8">
      {/* AuraBot full-access banner */}
      {isAuraBot && (
        <div
          className="border-accent bg-accent-weak dark:border-accent dark:bg-accent-weak/20 flex items-start gap-3 rounded-lg border p-4"
          data-testid="aurabot-full-access-banner"
        >
          <ShieldCheckIcon className="text-accent dark:text-accent mt-0.5 h-5 w-5 flex-shrink-0" />
          <div>
            <p className="text-accent dark:text-accent text-sm font-medium">
              {t('ai.colleagues.scope.fullAccess', undefined, 'Full Access Agent')}
            </p>
            <p className="text-accent dark:text-accent mt-0.5 text-sm">
              {t(
                'ai.colleagues.scope.fullAccessDesc',
                undefined,
                'AuraBot has full access to all data models and operations. This cannot be modified.',
              )}
            </p>
          </div>
        </div>
      )}

      {/* Section 1: Data Model Access */}
      <section>
        <h3 className="text-text mb-1 text-sm font-semibold tracking-wide uppercase dark:text-white">
          {t('ai.colleagues.scope.modelAccess', undefined, 'Data Model Access')}
        </h3>
        <p className="text-text-3 dark:text-text-3 mb-4 text-sm">
          {t(
            'ai.colleagues.scope.modelAccessDesc',
            undefined,
            'Choose which data models this agent can read and write.',
          )}
        </p>

        {/* All-access toggle */}
        <label
          className={`mb-4 flex items-center gap-3 rounded-lg border p-3 transition-colors ${
            allAccess
              ? 'border-accent bg-accent-weak dark:border-accent dark:bg-accent-weak/20'
              : 'border-border bg-panel dark:border-border dark:bg-subtle'
          } ${readOnly ? 'cursor-not-allowed opacity-70' : 'hover:border-accent dark:hover:border-accent cursor-pointer'}`}
          data-testid="all-models-toggle"
        >
          <input
            type="checkbox"
            checked={allAccess}
            onChange={toggleAllAccess}
            disabled={readOnly}
            className="border-border-strong text-accent focus:ring-accent0 h-4 w-4 rounded disabled:cursor-not-allowed disabled:opacity-50"
          />
          <div>
            <span className="text-text text-sm font-medium dark:text-white">
              {t('ai.colleagues.scope.allModels', undefined, 'Access all models')}
            </span>
            <span className="text-text-3 dark:text-text-3 block text-xs">
              {t(
                'ai.colleagues.scope.allModelsHint',
                undefined,
                'Agent can access all current and future models (like AuraBot).',
              )}
            </span>
          </div>
        </label>

        {/* Per-group model selection */}
        {loadingModels
          ? skeleton
          : !allAccess && (
              <div className="space-y-3" data-testid="model-groups-container">
                {sortedGroups.map(([group, models]) => {
                  const allGroupSelected = models.every((m) => selectedModels.has(m.code));
                  const someGroupSelected = models.some((m) => selectedModels.has(m.code));
                  return (
                    <div
                      key={group}
                      className="border-border dark:border-border overflow-hidden rounded-lg border"
                    >
                      {/* Group header */}
                      <label
                        className={`bg-subtle dark:bg-subtle/50 flex items-center gap-3 px-4 py-2.5 ${readOnly ? 'cursor-not-allowed' : 'cursor-pointer'}`}
                        data-testid={`model-group-${group}`}
                      >
                        <input
                          type="checkbox"
                          checked={allGroupSelected}
                          ref={(el) => {
                            if (el) el.indeterminate = someGroupSelected && !allGroupSelected;
                          }}
                          onChange={() => toggleGroupAll(models)}
                          disabled={readOnly}
                          className="border-border-strong text-accent focus:ring-accent0 h-4 w-4 rounded disabled:cursor-not-allowed disabled:opacity-50"
                        />
                        <span className="text-text-2 dark:text-text-3 text-sm font-semibold">
                          {group}
                        </span>
                        <span className="text-text-3 dark:text-text-3 ml-auto text-xs">
                          {models.filter((m) => selectedModels.has(m.code)).length} /{' '}
                          {models.length}
                        </span>
                      </label>
                      {/* Model checkboxes */}
                      <div className="grid grid-cols-2 gap-x-4 gap-y-1 px-4 py-2 sm:grid-cols-3">
                        {models
                          .sort((a, b) => a.code.localeCompare(b.code))
                          .map((m) => (
                            <label
                              key={m.code}
                              className={`flex items-center gap-2 py-1 ${readOnly ? 'cursor-not-allowed opacity-70' : 'cursor-pointer'}`}
                              data-testid={`model-check-${m.code}`}
                            >
                              <input
                                type="checkbox"
                                checked={selectedModels.has(m.code)}
                                onChange={() => toggleModel(m.code)}
                                disabled={readOnly}
                                className="border-border-strong text-accent focus:ring-accent0 h-3.5 w-3.5 rounded disabled:cursor-not-allowed disabled:opacity-50"
                              />
                              <span
                                className="text-text-2 dark:text-text-3 truncate text-sm"
                                title={m.displayName ?? m.code}
                              >
                                {m.code}
                              </span>
                            </label>
                          ))}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

        {/* When allAccess is true and not loading, show summary */}
        {!loadingModels && allAccess && !isAuraBot && (
          <div className="text-text-3 dark:text-text-3 flex items-center gap-2 px-1 text-sm">
            <InformationCircleIcon className="h-4 w-4 flex-shrink-0" />
            <span>
              {t(
                'ai.colleagues.scope.allModelsActive',
                undefined,
                `All ${allModels.length} published models are accessible.`,
              )}
            </span>
          </div>
        )}
      </section>

      {/* Section 2: Operation Permissions */}
      <section>
        <h3 className="text-text mb-1 text-sm font-semibold tracking-wide uppercase dark:text-white">
          {t('ai.colleagues.scope.operations', undefined, 'Operation Permissions')}
        </h3>
        <p className="text-text-3 dark:text-text-3 mb-4 text-sm">
          {t(
            'ai.colleagues.scope.operationsDesc',
            undefined,
            'Control which types of operations this agent can perform.',
          )}
        </p>

        <div className="space-y-2" data-testid="operations-container">
          {ALL_OPERATIONS.map((op) => {
            const meta = OPERATION_LABELS[op];
            const checked = selectedOps.has(op);
            return (
              <label
                key={op}
                className={`flex items-center gap-3 rounded-lg border p-3 transition-colors ${
                  checked
                    ? 'border-accent bg-accent-weak/50 dark:border-accent dark:bg-accent-weak/10'
                    : 'border-border bg-panel dark:border-border dark:bg-subtle'
                } ${readOnly ? 'cursor-not-allowed opacity-70' : 'hover:border-accent dark:hover:border-accent cursor-pointer'}`}
                data-testid={`op-toggle-${op}`}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggleOp(op)}
                  disabled={readOnly}
                  className="border-border-strong text-accent focus:ring-accent0 h-4 w-4 rounded disabled:cursor-not-allowed disabled:opacity-50"
                />
                <div>
                  <span className="text-text text-sm font-medium dark:text-white">
                    {meta.label}
                  </span>
                  <span className="text-text-3 dark:text-text-3 block text-xs">
                    {meta.description}
                  </span>
                </div>
              </label>
            );
          })}
        </div>
      </section>

      {/* Save button for scope */}
      {!readOnly && dirty && (
        <div className="border-border dark:border-border border-t pt-4">
          <button
            onClick={handleSaveScope}
            disabled={saving}
            className="bg-accent hover:bg-accent-hover inline-flex items-center gap-1.5 rounded-lg px-5 py-2.5 text-sm font-medium text-white transition-colors disabled:opacity-50"
            data-testid="scope-save-btn"
          >
            <CheckIcon className="h-4 w-4" />
            {saving
              ? t('ai.colleagues.action.saving', undefined, 'Saving...')
              : t('ai.colleagues.action.saveScope', undefined, 'Save Scope Changes')}
          </button>
        </div>
      )}

      {/* Section 3: Platform Tools (existing) */}
      <section>
        <h3 className="text-text mb-3 text-sm font-semibold tracking-wide uppercase dark:text-white">
          {t('ai.colleagues.tools.available', undefined, 'Available Tools')}
        </h3>
        {loadingTools ? (
          skeleton
        ) : tools.length === 0 ? (
          <div className="text-text-3 dark:text-text-3 py-8 text-center">
            <WrenchScrewdriverIcon className="text-text-3 dark:text-text-2 mx-auto mb-2 h-10 w-10" />
            <p className="text-sm">
              {t('ai.colleagues.tools.noTools', undefined, 'No tools configured')}
            </p>
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-text-3 dark:text-text-3 text-left">
                <th className="border-border dark:border-border border-b p-2 font-medium">Code</th>
                <th className="border-border dark:border-border border-b p-2 font-medium">Name</th>
                <th className="border-border dark:border-border border-b p-2 font-medium">Type</th>
              </tr>
            </thead>
            <tbody>
              {tools.map((row) => (
                <tr
                  key={row.pid}
                  className="border-border hover:bg-subtle dark:border-border dark:hover:bg-subtle/50 border-b"
                >
                  <td className="text-text-2 dark:text-text-3 p-2 font-mono text-xs">
                    {row.tool_code}
                  </td>
                  <td className="text-text p-2 dark:text-white">{row.tool_name}</td>
                  <td className="p-2">
                    <span className="bg-subtle text-text-2 dark:bg-subtle dark:text-text-3 inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium">
                      {row.tool_type}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* Skills */}
      <section>
        <h3 className="text-text mb-3 text-sm font-semibold tracking-wide uppercase dark:text-white">
          {t('ai.colleagues.tools.skills', undefined, 'Skills')}
        </h3>
        {loadingSkills ? (
          skeleton
        ) : skills.length === 0 ? (
          <div className="text-text-3 dark:text-text-3 py-8 text-center">
            <SparklesIcon className="text-text-3 dark:text-text-2 mx-auto mb-2 h-10 w-10" />
            <p className="text-sm">
              {t('ai.colleagues.tools.noSkills', undefined, 'No skills configured')}
            </p>
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-text-3 dark:text-text-3 text-left">
                <th className="border-border dark:border-border border-b p-2 font-medium">Code</th>
                <th className="border-border dark:border-border border-b p-2 font-medium">Name</th>
                <th className="border-border dark:border-border border-b p-2 font-medium">
                  Category
                </th>
                <th className="border-border dark:border-border border-b p-2 font-medium">
                  Execution Mode
                </th>
              </tr>
            </thead>
            <tbody>
              {skills.map((row) => (
                <tr
                  key={row.pid}
                  className="border-border hover:bg-subtle dark:border-border dark:hover:bg-subtle/50 border-b"
                >
                  <td className="text-text-2 dark:text-text-3 p-2 font-mono text-xs">
                    {row.skill_code}
                  </td>
                  <td className="text-text p-2 dark:text-white">{row.skill_name}</td>
                  <td className="text-text-2 dark:text-text-3 p-2">{row.skill_category ?? '-'}</td>
                  <td className="p-2">
                    <span className="bg-accent-weak text-accent dark:bg-accent-weak/30 dark:text-accent inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium">
                      {row.execution_mode ?? '-'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Run History Tab
// ---------------------------------------------------------------------------

function RunHistoryTab({ agentCode }: { agentCode: string }) {
  const { t } = useI18n();
  const [runs, setRuns] = useState<RunRecord[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    (async () => {
      try {
        const filters = JSON.stringify([
          { fieldName: 'agent_code', operator: 'eq', value: agentCode },
        ]);
        const res = await get<{ records: RunRecord[] }>('/api/dynamic/agent-run/list', {
          pageNum: 1,
          pageSize: 20,
          sortField: 'created_at',
          sortOrder: 'DESC',
          filters,
        });
        if (ResultHelper.isSuccess(res) && res.data?.records) {
          setRuns(res.data.records);
        }
      } catch {
        // silent
      } finally {
        setLoading(false);
      }
    })();
  }, [agentCode]);

  if (loading) {
    return (
      <div className="space-y-3">
        {[1, 2, 3].map((i) => (
          <div key={i} className="bg-subtle dark:bg-subtle h-12 animate-pulse rounded-lg" />
        ))}
      </div>
    );
  }

  if (runs.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <ClockIcon className="text-text-3 dark:text-text-2 mb-3 h-12 w-12" />
        <h3 className="text-text text-lg font-medium dark:text-white">
          {t('ai.colleagues.runs.empty', undefined, 'No runs yet')}
        </h3>
        <p className="text-text-3 dark:text-text-3 mt-1 text-sm">
          {t(
            'ai.colleagues.runs.emptyDesc',
            undefined,
            'Run history will appear here after the agent executes tasks.',
          )}
        </p>
      </div>
    );
  }

  const statusColor = (s: string) => {
    switch (s?.toLowerCase()) {
      case 'completed':
        return 'bg-status-green-bg text-status-green dark:bg-status-green-bg/30 dark:text-status-green';
      case 'running':
        return 'bg-accent-weak text-accent dark:bg-accent-weak/30 dark:text-accent';
      case 'failed':
        return 'bg-status-red-bg text-status-red dark:bg-status-red-bg/30 dark:text-status-red';
      default:
        return 'bg-subtle text-text-2 dark:bg-subtle dark:text-text-3';
    }
  };

  const formatDuration = (ms: number | null) => {
    if (ms == null) return '-';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  return (
    <div className="max-w-4xl">
      <p className="text-text-3 dark:text-text-3 mb-4 text-sm">
        {t('ai.colleagues.runs.count', { count: runs.length }, `${runs.length} recent runs`)}
      </p>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-text-3 dark:text-text-3 text-left">
            <th className="border-border dark:border-border border-b p-2 font-medium">Task</th>
            <th className="border-border dark:border-border border-b p-2 font-medium">Status</th>
            <th className="border-border dark:border-border border-b p-2 font-medium">Model</th>
            <th className="border-border dark:border-border border-b p-2 font-medium">Duration</th>
            <th className="border-border dark:border-border border-b p-2 font-medium">Tokens</th>
            <th className="border-border dark:border-border border-b p-2 font-medium">Started</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => (
            <tr
              key={run.pid}
              className="border-border hover:bg-subtle dark:border-border dark:hover:bg-subtle/50 border-b"
            >
              <td className="text-text max-w-[200px] truncate p-2 dark:text-white">
                {run.task_title ?? '-'}
              </td>
              <td className="p-2">
                <span
                  className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium ${statusColor(run.run_status)}`}
                >
                  {run.run_status}
                </span>
              </td>
              <td className="text-text-2 dark:text-text-3 p-2 font-mono text-xs">
                {run.model ?? '-'}
              </td>
              <td className="text-text-2 dark:text-text-3 p-2">
                {formatDuration(run.duration_ms)}
              </td>
              <td className="text-text-2 dark:text-text-3 p-2">
                {run.input_tokens || run.output_tokens
                  ? `${run.input_tokens ?? 0} / ${run.output_tokens ?? 0}`
                  : '-'}
              </td>
              <td className="text-text-3 dark:text-text-3 p-2 text-xs">
                {run.started_at ? new Date(run.started_at).toLocaleString() : '-'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Schedules Tab
// ---------------------------------------------------------------------------

interface ScheduleRecord {
  pid: string;
  title: string;
  cron_expression: string | null;
  schedule_status: string;
  next_run_at: string | null;
  last_run_at: string | null;
  timezone: string | null;
  daily_run_budget: number | null;
  concurrency_limit: number | null;
  last_block_reason: string | null;
}

export function SchedulesTab({ agentCode }: { agentCode: string }) {
  const { t } = useI18n();
  const { timezone, formats } = useTimezone();
  const toast = useToastContext();
  const [schedules, setSchedules] = useState<ScheduleRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [confirmTrigger, setConfirmTrigger] = useState<ScheduleRecord | null>(null);
  const [triggeringPid, setTriggeringPid] = useState<string | null>(null);

  const loadSchedules = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const filters = JSON.stringify([
        { fieldName: 'agent_code', operator: 'eq', value: agentCode },
      ]);
      const res = await get<{ records: ScheduleRecord[] }>('/api/dynamic/agent-schedule/list', {
        pageNum: 1,
        pageSize: 20,
        filters,
      });
      if (!ResultHelper.isSuccess(res) || !res.data?.records) {
        throw new Error('Schedule list request failed');
      }
      setSchedules(res.data.records);
    } catch {
      setSchedules([]);
      setLoadError(true);
    } finally {
      setLoading(false);
    }
  }, [agentCode]);

  useEffect(() => {
    void loadSchedules();
  }, [loadSchedules]);

  const triggerNow = async () => {
    if (!confirmTrigger) return;
    setTriggeringPid(confirmTrigger.pid);
    try {
      const response = await post<{ taskPid: string; schedulePid: string }>(
        `/api/agent/schedule/${confirmTrigger.pid}/trigger`,
        {},
      );
      if (!ResultHelper.isSuccess(response) || !response.data?.taskPid) {
        throw new Error('schedule trigger failed');
      }
      toast.showSuccessToast(
        t(
          'ai.colleagues.schedules.triggered',
          undefined,
          'Schedule started through the governed runtime',
        ),
      );
      setConfirmTrigger(null);
      await loadSchedules();
    } catch {
      toast.showErrorToast(
        t(
          'ai.colleagues.schedules.triggerFailed',
          undefined,
          'Run now was blocked or failed. No duplicate task was created.',
        ),
      );
    } finally {
      setTriggeringPid(null);
    }
  };

  if (loading) {
    return (
      <div className="space-y-3">
        {[1, 2, 3].map((i) => (
          <div key={i} className="bg-subtle dark:bg-subtle h-12 animate-pulse rounded-lg" />
        ))}
      </div>
    );
  }

  if (loadError) {
    return (
      <div
        className="border-status-red bg-status-red-bg dark:border-status-red/60 dark:bg-status-red-bg/20 flex flex-col items-center justify-center rounded-xl border px-6 py-12 text-center"
        data-testid="agent-schedules-error"
      >
        <CalendarDaysIcon className="text-status-red mb-3 h-10 w-10" />
        <h3 className="text-status-red dark:text-status-red text-base font-medium">
          {t('ai.colleagues.schedules.error', undefined, 'Schedules could not be loaded')}
        </h3>
        <button
          type="button"
          onClick={() => void loadSchedules()}
          className="border-status-red bg-panel text-status-red hover:bg-status-red-bg dark:border-status-red dark:bg-status-red-bg dark:text-status-red mt-4 rounded-lg border px-4 py-2 text-sm font-medium"
          data-testid="retry-agent-schedules"
        >
          {t('ai.colleagues.schedules.retry', undefined, 'Try again')}
        </button>
      </div>
    );
  }

  if (schedules.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <CalendarDaysIcon className="text-text-3 dark:text-text-2 mb-3 h-12 w-12" />
        <h3 className="text-text text-lg font-medium dark:text-white">
          {t('ai.colleagues.schedules.empty', undefined, 'No schedules')}
        </h3>
        <p className="text-text-3 dark:text-text-3 mt-1 text-sm">
          {t(
            'ai.colleagues.schedules.emptyDesc',
            undefined,
            'Scheduled tasks will appear here once configured.',
          )}
        </p>
        <button
          type="button"
          onClick={() =>
            window.location.assign(
              `/dynamic/agent-schedule?agentCode=${encodeURIComponent(agentCode)}`,
            )
          }
          className="bg-accent hover:bg-accent-hover mt-4 rounded-lg px-4 py-2 text-sm font-medium text-white"
          data-testid="manage-agent-schedules"
        >
          {t('ai.colleagues.schedules.manage', undefined, 'Manage schedules')}
        </button>
      </div>
    );
  }

  const statusColor = (s: string) => {
    switch (s?.toLowerCase()) {
      case 'active':
        return 'bg-status-green-bg text-status-green dark:bg-status-green-bg/30 dark:text-status-green';
      case 'paused':
        return 'bg-status-amber-bg text-status-amber dark:bg-status-amber-bg/30 dark:text-status-amber';
      case 'disabled':
        return 'bg-subtle text-text-2 dark:bg-subtle dark:text-text-3';
      default:
        return 'bg-subtle text-text-2 dark:bg-subtle dark:text-text-3';
    }
  };

  return (
    <div className="max-w-4xl">
      <div className="mb-4 flex items-center justify-between gap-3">
        <p className="text-text-3 dark:text-text-3 text-sm">
          {t(
            'ai.colleagues.schedules.count',
            { count: schedules.length },
            `${schedules.length} schedules`,
          )}
        </p>
        <button
          type="button"
          onClick={() =>
            window.location.assign(
              `/dynamic/agent-schedule?agentCode=${encodeURIComponent(agentCode)}`,
            )
          }
          className="bg-accent hover:bg-accent-hover rounded-lg px-3 py-2 text-sm font-medium text-white"
          data-testid="manage-agent-schedules"
        >
          {t('ai.colleagues.schedules.manage', undefined, 'Manage schedules')}
        </button>
      </div>
      {confirmTrigger && (
        <div
          className="border-status-amber bg-status-amber-bg dark:border-status-amber dark:bg-status-amber-bg/30 mb-4 rounded-xl border p-4"
          role="dialog"
          aria-label={t('ai.colleagues.schedules.confirmRunNow', undefined, 'Run schedule now')}
        >
          <p className="text-text font-medium dark:text-white">
            {t('ai.colleagues.schedules.confirmRunNow', undefined, 'Run schedule now')}
          </p>
          <p className="text-text-2 dark:text-text-3 mt-1 text-sm">
            {confirmTrigger.title} ·{' '}
            {t(
              'ai.colleagues.schedules.policyNotice',
              undefined,
              'Budget, concurrency, employee identity and approval policy will be enforced.',
            )}
          </p>
          <div className="mt-3 flex gap-2">
            <button
              type="button"
              onClick={() => void triggerNow()}
              disabled={triggeringPid === confirmTrigger.pid}
              className="bg-status-amber rounded-lg px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
              data-testid="confirm-run-schedule-now"
            >
              {triggeringPid === confirmTrigger.pid
                ? t('common.running', undefined, 'Running...')
                : t('ai.colleagues.schedules.runNow', undefined, 'Run now')}
            </button>
            <button
              type="button"
              onClick={() => setConfirmTrigger(null)}
              disabled={triggeringPid === confirmTrigger.pid}
              className="border-border-strong rounded-lg border px-3 py-2 text-sm"
            >
              {t('common.cancel', undefined, 'Cancel')}
            </button>
          </div>
        </div>
      )}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[860px] text-sm">
          <thead>
            <tr className="text-text-3 dark:text-text-3 text-left">
              <th className="border-border dark:border-border border-b p-2 font-medium">
                {t('ai.colleagues.schedules.name', undefined, 'Name')}
              </th>
              <th className="border-border dark:border-border border-b p-2 font-medium">
                {t('ai.colleagues.schedules.cron', undefined, 'Cron / timezone')}
              </th>
              <th className="border-border dark:border-border border-b p-2 font-medium">
                {t('ai.colleagues.schedules.status', undefined, 'Status')}
              </th>
              <th className="border-border dark:border-border border-b p-2 font-medium">
                {t('ai.colleagues.schedules.budget', undefined, 'Budget / concurrency')}
              </th>
              <th className="border-border dark:border-border border-b p-2 font-medium">
                {t('ai.colleagues.schedules.lastRun', undefined, 'Last run / decision')}
              </th>
              <th className="border-border dark:border-border border-b p-2 font-medium">
                {t('common.actions', undefined, 'Actions')}
              </th>
            </tr>
          </thead>
          <tbody>
            {schedules.map((row) => (
              <tr
                key={row.pid}
                className="border-border hover:bg-subtle dark:border-border dark:hover:bg-subtle/50 border-b"
              >
                <td className="text-text p-2 dark:text-white">{row.title}</td>
                <td className="text-text-2 dark:text-text-3 p-2 font-mono text-xs">
                  {row.cron_expression ?? '-'} · {row.timezone ?? 'UTC'}
                </td>
                <td className="p-2">
                  <span
                    className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium ${statusColor(row.schedule_status)}`}
                  >
                    {t(
                      `ai.colleagues.schedules.status${row.schedule_status
                        .charAt(0)
                        .toUpperCase()}${row.schedule_status.slice(1).toLowerCase()}`,
                      undefined,
                      row.schedule_status,
                    )}
                  </span>
                </td>
                <td className="text-text-3 dark:text-text-3 p-2 text-xs">
                  {t(
                    'ai.colleagues.schedules.budgetSummary',
                    {
                      daily: row.daily_run_budget ?? 24,
                      concurrency: row.concurrency_limit ?? 1,
                    },
                    `${row.daily_run_budget ?? 24} per day · ${
                      row.concurrency_limit ?? 1
                    } concurrent`,
                  )}
                </td>
                <td className="text-text-3 dark:text-text-3 p-2 text-xs">
                  {row.last_run_at
                    ? formatInTimezone(row.last_run_at, formats.datetime, timezone)
                    : '-'}
                  {row.last_block_reason ? ` · ${row.last_block_reason}` : ''}
                </td>
                <td className="p-2">
                  <button
                    type="button"
                    onClick={() => setConfirmTrigger(row)}
                    disabled={row.schedule_status !== 'active' || triggeringPid !== null}
                    className="border-accent text-accent hover:bg-accent-weak dark:border-accent dark:text-accent rounded-lg border px-2.5 py-1.5 text-xs font-medium whitespace-nowrap disabled:cursor-not-allowed disabled:opacity-50"
                    data-testid={`run-schedule-now-${row.pid}`}
                  >
                    {t('ai.colleagues.schedules.runNow', undefined, 'Run now')}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Knowledge Bases Tab
// ---------------------------------------------------------------------------

function parseKnowledgeBaseIds(raw: AgentDetail['knowledge_base_ids']): string[] {
  if (Array.isArray(raw)) {
    return [
      ...new Set(
        raw
          .filter((value) => typeof value === 'string' && value.trim())
          .map((value) => value.trim()),
      ),
    ];
  }
  if (!raw || raw === '*') return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed)
      ? [
          ...new Set(
            parsed
              .filter((value) => typeof value === 'string' && value.trim())
              .map((value) => value.trim()),
          ),
        ]
      : [];
  } catch {
    return [];
  }
}

function KnowledgeBasesTab({
  agent,
  readOnly,
  onSave,
  saving,
  onManageKnowledge,
}: {
  agent: AgentDetail;
  readOnly: boolean;
  onSave: (data: Partial<AgentDetail>) => void;
  saving: boolean;
  onManageKnowledge: () => void;
}) {
  const { t } = useI18n();
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [selectedIds, setSelectedIds] = useState<string[]>(() =>
    parseKnowledgeBaseIds(agent.knowledge_base_ids),
  );
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    setSelectedIds(parseKnowledgeBaseIds(agent.knowledge_base_ids));
  }, [agent.knowledge_base_ids]);

  const fetchKnowledgeBases = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const res = await get<KnowledgeBase[]>('/api/ai/knowledge');
      if (ResultHelper.isSuccess(res)) {
        setKnowledgeBases(res.data ?? []);
      } else {
        setLoadError(true);
      }
    } catch {
      setLoadError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void fetchKnowledgeBases();
  }, [fetchKnowledgeBases]);

  const toggleKnowledgeBase = (knowledgeBase: KnowledgeBase) => {
    const isSelected = selectedIds.includes(knowledgeBase.pid);
    if (readOnly || (!isSelected && knowledgeBase.status !== 'active')) return;
    setSelectedIds((current) =>
      isSelected
        ? current.filter((pid) => pid !== knowledgeBase.pid)
        : [...current, knowledgeBase.pid],
    );
  };

  if (readOnly) {
    return (
      <div
        className="border-accent bg-accent-weak dark:border-accent dark:bg-accent-weak/30 max-w-2xl rounded-xl border p-5"
        data-testid="aurabot-knowledge-policy"
      >
        <div className="flex gap-3">
          <InformationCircleIcon className="text-accent dark:text-accent mt-0.5 h-5 w-5 shrink-0" />
          <div>
            <h3 className="text-accent dark:text-accent font-semibold">
              {t(
                'ai.colleagues.knowledge.aurabotTitle',
                undefined,
                'AuraBot uses tenant knowledge',
              )}
            </h3>
            <p className="text-accent dark:text-accent mt-1 text-sm leading-6">
              {t(
                'ai.colleagues.knowledge.aurabotDescription',
                undefined,
                'AuraBot is the general assistant. Choose knowledge bases in each conversation; per-colleague bindings apply only to named AI colleagues.',
              )}
            </p>
            <button
              type="button"
              onClick={onManageKnowledge}
              className="text-accent dark:text-accent mt-3 text-sm font-medium hover:underline"
              data-testid="manage-knowledge-bases-link"
            >
              {t('ai.colleagues.knowledge.manage', undefined, 'Manage knowledge bases')}
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-3xl space-y-5" data-testid="agent-knowledge-tab-panel">
      <div>
        <h3 className="text-text text-sm font-semibold tracking-wide uppercase dark:text-white">
          {t('ai.colleagues.knowledge.title', undefined, 'Assigned knowledge bases')}
        </h3>
        <p className="text-text-3 dark:text-text-3 mt-2 text-sm leading-6">
          {t(
            'ai.colleagues.knowledge.description',
            undefined,
            'This colleague reads only the knowledge bases selected here. A per-conversation selection overrides this list; an empty list does not grant tenant-wide access.',
          )}
        </p>
      </div>

      {loading && (
        <div className="space-y-3" data-testid="agent-knowledge-loading">
          {[0, 1].map((item) => (
            <div key={item} className="bg-subtle dark:bg-subtle h-20 animate-pulse rounded-xl" />
          ))}
        </div>
      )}

      {!loading && loadError && (
        <div
          className="border-status-red bg-status-red-bg dark:border-status-red dark:bg-status-red-bg/30 rounded-xl border p-4"
          data-testid="agent-knowledge-error"
        >
          <p className="text-status-red dark:text-status-red text-sm">
            {t(
              'ai.colleagues.knowledge.loadFailed',
              undefined,
              'Knowledge bases could not be loaded.',
            )}
          </p>
          <button
            type="button"
            onClick={() => void fetchKnowledgeBases()}
            className="text-status-red dark:text-status-red mt-2 text-sm font-medium hover:underline"
            data-testid="agent-knowledge-retry"
          >
            {t('common.retry', undefined, 'Retry')}
          </button>
        </div>
      )}

      {!loading && !loadError && knowledgeBases.length === 0 && (
        <div
          className="border-border-strong dark:border-border rounded-xl border border-dashed p-8 text-center"
          data-testid="agent-knowledge-empty"
        >
          <CircleStackIcon className="text-text-3 dark:text-text-2 mx-auto h-10 w-10" />
          <p className="text-text-2 dark:text-text-3 mt-3 text-sm font-medium">
            {t('ai.colleagues.knowledge.empty', undefined, 'No knowledge bases yet')}
          </p>
          <button
            type="button"
            onClick={onManageKnowledge}
            className="text-accent dark:text-accent mt-2 text-sm font-medium hover:underline"
            data-testid="agent-knowledge-create-link"
          >
            {t('ai.colleagues.knowledge.create', undefined, 'Create a knowledge base')}
          </button>
        </div>
      )}

      {!loading && !loadError && knowledgeBases.length > 0 && (
        <div className="space-y-3" data-testid="agent-knowledge-options">
          {knowledgeBases.map((knowledgeBase) => {
            const selected = selectedIds.includes(knowledgeBase.pid);
            const unavailable = knowledgeBase.status !== 'active';
            const disabled = unavailable && !selected;
            return (
              <button
                type="button"
                key={knowledgeBase.pid}
                onClick={() => toggleKnowledgeBase(knowledgeBase)}
                disabled={disabled}
                aria-pressed={selected}
                className={`flex w-full items-start gap-3 rounded-xl border p-4 text-left transition-colors ${
                  selected
                    ? 'border-accent0 bg-accent-weak dark:border-accent0 dark:bg-accent-weak/30'
                    : 'border-border bg-panel hover:border-border-strong dark:border-border dark:bg-subtle'
                } ${disabled ? 'cursor-not-allowed opacity-55' : ''}`}
                data-testid={`agent-knowledge-option-${knowledgeBase.pid}`}
              >
                <span
                  className={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded border ${
                    selected
                      ? 'border-accent bg-accent text-white'
                      : 'border-border-strong dark:border-border-strong'
                  }`}
                  aria-hidden="true"
                >
                  {selected && <CheckIcon className="h-3.5 w-3.5" />}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="text-text font-medium dark:text-white">
                      {knowledgeBase.name}
                    </span>
                    {unavailable && (
                      <span className="bg-status-amber-bg text-status-amber dark:bg-status-amber-bg/40 dark:text-status-amber rounded-full px-2 py-0.5 text-xs font-medium">
                        {t('ai.colleagues.knowledge.disabled', undefined, 'Disabled')}
                      </span>
                    )}
                  </span>
                  {knowledgeBase.description && (
                    <span className="text-text-3 dark:text-text-3 mt-1 block text-sm">
                      {knowledgeBase.description}
                    </span>
                  )}
                  <span className="text-text-3 dark:text-text-3 mt-2 block text-xs">
                    {t(
                      'ai.colleagues.knowledge.counts',
                      { documents: knowledgeBase.docCount, chunks: knowledgeBase.chunkCount },
                      `${knowledgeBase.docCount} documents · ${knowledgeBase.chunkCount} chunks`,
                    )}
                  </span>
                </span>
              </button>
            );
          })}
        </div>
      )}

      <div className="border-border dark:border-border flex items-center justify-between border-t pt-4">
        <p
          className="text-text-3 dark:text-text-3 text-xs"
          data-testid="agent-knowledge-selection-count"
        >
          {t(
            'ai.colleagues.knowledge.selectedCount',
            { count: selectedIds.length },
            `${selectedIds.length} selected`,
          )}
        </p>
        <button
          type="button"
          onClick={() => onSave({ knowledge_base_ids: selectedIds })}
          disabled={saving || loading || loadError}
          className="bg-accent hover:bg-accent-hover inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
          data-testid="agent-knowledge-save"
        >
          <CheckIcon className="h-4 w-4" />
          {saving
            ? t('common.saving', undefined, 'Saving...')
            : t('common.save', undefined, 'Save')}
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Memory Tab
// ---------------------------------------------------------------------------

function MemoryTab({ agentPid }: { agentPid: string }) {
  const { t } = useI18n();
  const [memories, setMemories] = useState<MemoryItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    (async () => {
      try {
        const res = await get<{ records: MemoryItem[] }>('/api/dynamic/agent-memory/list', {
          pageNum: 1,
          pageSize: 50,
          filters: JSON.stringify([
            { fieldName: 'memory_agent_id', operator: 'eq', value: agentPid },
          ]),
        });
        if (ResultHelper.isSuccess(res) && res.data?.records) {
          setMemories(res.data.records);
        }
      } catch {
        // silent — memory may not be available
      } finally {
        setLoading(false);
      }
    })();
  }, [agentPid]);

  if (loading) {
    return (
      <div className="space-y-3">
        {[1, 2, 3].map((i) => (
          <div key={i} className="bg-subtle dark:bg-subtle h-20 animate-pulse rounded-lg" />
        ))}
      </div>
    );
  }

  if (memories.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <BookOpenIcon className="text-text-3 dark:text-text-2 mb-3 h-12 w-12" />
        <h3 className="text-text text-lg font-medium dark:text-white">
          {t('ai.colleagues.memory.empty', undefined, 'No memories yet')}
        </h3>
        <p className="text-text-3 dark:text-text-3 mt-1 text-sm">
          {t(
            'ai.colleagues.memory.emptyDesc',
            undefined,
            'Memories will appear here as the agent learns from interactions.',
          )}
        </p>
      </div>
    );
  }

  return (
    <div className="max-w-3xl space-y-3">
      <p className="text-text-3 dark:text-text-3 mb-4 text-sm">
        {t('ai.colleagues.memory.count', { count: memories.length }, `${memories.length} memories`)}
      </p>
      {memories.map((mem) => (
        <div
          key={mem.pid}
          className="border-border bg-panel dark:border-border dark:bg-subtle rounded-lg border p-4"
        >
          <div className="mb-2 flex items-center gap-2">
            <span className="bg-accent-weak text-accent dark:bg-accent-weak/30 dark:text-accent inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium">
              {mem.memory_type}
            </span>
            {mem.category && (
              <span className="bg-subtle text-text-2 dark:bg-subtle dark:text-text-3 inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium">
                {mem.category}
              </span>
            )}
            <span className="text-text-3 ml-auto text-xs">
              {new Date(mem.created_at).toLocaleDateString()}
            </span>
          </div>
          {mem.memory_title && (
            <h4 className="text-text mb-1 text-sm font-medium dark:text-white">
              {mem.memory_title}
            </h4>
          )}
          <p className="text-text-2 dark:text-text-3 line-clamp-4 text-sm whitespace-pre-wrap">
            {mem.memory_content}
          </p>
        </div>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Enroll Employee Dialog
// ---------------------------------------------------------------------------

function flattenDepts(
  nodes: DepartmentTreeNode[],
  depth = 0,
): { pid: string; name: string; depth: number }[] {
  const result: { pid: string; name: string; depth: number }[] = [];
  for (const node of nodes) {
    result.push({ pid: node.pid, name: node.name, depth });
    if (node.children?.length) {
      result.push(...flattenDepts(node.children, depth + 1));
    }
  }
  return result;
}

function EnrollEmployeeDialog({
  agentPid,
  agentName,
  onClose,
  onSuccess,
}: {
  agentPid: string;
  agentName: string;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const { t } = useI18n();
  const toast = useToastContext();
  const [departments, setDepartments] = useState<{ pid: string; name: string; depth: number }[]>(
    [],
  );
  const [positions, setPositions] = useState<PositionItem[]>([]);
  const [selectedDeptPid, setSelectedDeptPid] = useState('');
  const [selectedPosPid, setSelectedPosPid] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [loadingDepts, setLoadingDepts] = useState(true);
  const [loadingPos, setLoadingPos] = useState(false);

  // Load department tree
  useEffect(() => {
    get<DepartmentTreeNode[]>('/api/org/departments/tree')
      .then((res) => {
        if (ResultHelper.isSuccess(res) && res.data) {
          setDepartments(flattenDepts(res.data));
        }
      })
      .finally(() => setLoadingDepts(false));
  }, []);

  // Load positions when department changes
  useEffect(() => {
    if (!selectedDeptPid) {
      setPositions([]);
      setSelectedPosPid('');
      return;
    }
    setLoadingPos(true);
    get<{ records: PositionItem[] }>('/api/dynamic/org_position/list', {
      params: {
        pageSize: 200,
        filters: JSON.stringify([
          { fieldName: 'org_pos_dept_id', operator: 'EQ', value: selectedDeptPid },
        ]),
      },
    })
      .then((res) => {
        if (ResultHelper.isSuccess(res) && res.data?.records) {
          setPositions(res.data.records.filter((p) => p.org_pos_status !== 'inactive'));
        } else {
          setPositions([]);
        }
      })
      .finally(() => setLoadingPos(false));
  }, [selectedDeptPid]);

  const handleSubmit = async () => {
    if (!selectedDeptPid) {
      toast.showErrorToast(
        t('ai.colleagues.enroll.error.deptRequired', undefined, 'Please select a department'),
      );
      return;
    }
    setSubmitting(true);
    try {
      const res = await post(`/api/agent/definitions/${agentPid}/enroll-employee`, {
        departmentPid: selectedDeptPid,
        positionPid: selectedPosPid || undefined,
      });
      if (ResultHelper.isSuccess(res)) {
        toast.showSuccessToast(
          t(
            'ai.colleagues.enroll.success',
            { name: agentName },
            `${agentName} has been enrolled as a digital employee`,
          ),
        );
        onSuccess();
      } else {
        toast.showErrorToast(
          t(
            'ai.colleagues.enroll.error.failed',
            undefined,
            'Enrollment failed. Ensure the agent has a system account.',
          ),
        );
      }
    } catch {
      toast.showErrorToast(t('ai.colleagues.enroll.error.failed', undefined, 'Enrollment failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const selectClass =
    'w-full rounded-lg border border-border-strong bg-panel px-3 py-2 text-sm text-text appearance-none dark:border-border-strong dark:bg-subtle dark:text-white focus:border-accent0 focus:ring-2 focus:ring-accent0 transition-colors disabled:opacity-50';

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
      data-testid="enroll-dialog-overlay"
    >
      <div
        className="bg-panel dark:bg-subtle relative w-full max-w-md rounded-2xl p-6 shadow-2xl"
        data-testid="enroll-dialog"
      >
        {/* Header */}
        <div className="mb-5 flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className="bg-accent-weak dark:bg-accent-weak/40 flex h-10 w-10 items-center justify-center rounded-xl">
              <BuildingOfficeIcon className="text-accent dark:text-accent h-5 w-5" />
            </div>
            <div>
              <h2 className="text-text text-base font-semibold dark:text-white">
                {t('ai.colleagues.enroll.title', undefined, 'Enroll as Employee')}
              </h2>
              <p className="text-text-3 dark:text-text-3 text-xs">
                {t(
                  'ai.colleagues.enroll.subtitle',
                  { name: agentName },
                  `Add ${agentName} to the org chart`,
                )}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-text-3 hover:bg-subtle hover:text-text-2 dark:hover:bg-subtle rounded-lg p-1.5"
          >
            <XMarkIcon className="h-5 w-5" />
          </button>
        </div>

        {/* Department */}
        <div className="mb-4">
          <label className="text-text-2 dark:text-text-3 mb-1.5 block text-sm font-medium">
            {t('ai.colleagues.enroll.field.department', undefined, 'Department')} *
          </label>
          {loadingDepts ? (
            <div className="bg-subtle dark:bg-subtle h-9 w-full animate-pulse rounded-lg" />
          ) : departments.length === 0 ? (
            /* A required field with nothing selectable is a dead end: the only
               feedback used to be "Please select a department" on submit, for a
               list that had none to offer. Say what is missing and where to fix it. */
            <div
              className="border-border-strong text-text-3 dark:border-border-strong dark:text-text-3 rounded-lg border border-dashed px-3 py-2.5 text-sm"
              data-testid="enroll-dept-empty"
            >
              {t(
                'ai.colleagues.enroll.empty.department',
                undefined,
                'No departments yet — create one under Organization first, then enrol this colleague.',
              )}
            </div>
          ) : (
            <div className="relative">
              <select
                value={selectedDeptPid}
                onChange={(e) => {
                  setSelectedDeptPid(e.target.value);
                  setSelectedPosPid('');
                }}
                className={selectClass}
                data-testid="enroll-dept-select"
              >
                <option value="">
                  {t(
                    'ai.colleagues.enroll.placeholder.department',
                    undefined,
                    '— Select Department —',
                  )}
                </option>
                {departments.map((d) => (
                  <option key={d.pid} value={d.pid}>
                    {'  '.repeat(d.depth)}
                    {d.depth > 0 ? '└ ' : ''}
                    {d.name}
                  </option>
                ))}
              </select>
              <ChevronDownIcon className="text-text-3 pointer-events-none absolute top-1/2 right-3 h-4 w-4 -translate-y-1/2" />
            </div>
          )}
        </div>

        {/* Position */}
        <div className="mb-6">
          <label className="text-text-2 dark:text-text-3 mb-1.5 block text-sm font-medium">
            {t('ai.colleagues.enroll.field.position', undefined, 'Position')} *
            <span className="text-text-3 ml-1 text-xs">
              {t('ai.colleagues.enroll.optional', undefined, '(optional)')}
            </span>
          </label>
          <div className="relative">
            <select
              value={selectedPosPid}
              onChange={(e) => setSelectedPosPid(e.target.value)}
              disabled={!selectedDeptPid || loadingPos}
              className={selectClass}
              data-testid="enroll-position-select"
            >
              <option value="">
                {loadingPos
                  ? t('ai.colleagues.enroll.loading', undefined, 'Loading...')
                  : t(
                      'ai.colleagues.enroll.placeholder.position',
                      undefined,
                      '— Select Position —',
                    )}
              </option>
              {positions.map((p) => (
                <option key={p.pid} value={p.pid}>
                  {p.org_pos_name}
                </option>
              ))}
            </select>
            <ChevronDownIcon className="text-text-3 pointer-events-none absolute top-1/2 right-3 h-4 w-4 -translate-y-1/2" />
          </div>
          {!selectedDeptPid && (
            <p className="text-text-3 mt-1 text-xs">
              {t(
                'ai.colleagues.enroll.hint.selectDeptFirst',
                undefined,
                'Select a department first to load positions',
              )}
            </p>
          )}
          {selectedDeptPid && !loadingPos && positions.length === 0 && (
            <p
              className="text-status-amber dark:text-status-amber0 mt-1 text-xs"
              data-testid="enroll-position-empty"
            >
              {t(
                'ai.colleagues.enroll.empty.position',
                undefined,
                'This department has no positions yet — add one under Organization before enrolling.',
              )}
            </p>
          )}
        </div>

        {/* Actions */}
        <div className="flex justify-end gap-3">
          <button
            onClick={onClose}
            disabled={submitting}
            className="border-border-strong text-text-2 hover:bg-subtle dark:border-border-strong dark:text-text-3 dark:hover:bg-subtle rounded-lg border px-4 py-2 text-sm font-medium disabled:opacity-50"
          >
            {t('common.cancel', undefined, 'Cancel')}
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting || !selectedDeptPid || !selectedPosPid}
            className="bg-accent hover:bg-accent-hover rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
            data-testid="enroll-confirm-btn"
          >
            {submitting
              ? t('ai.colleagues.enroll.enrolling', undefined, 'Enrolling...')
              : t('ai.colleagues.enroll.confirm', undefined, 'Enroll')}
          </button>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Remove from Org Confirmation Dialog
// ---------------------------------------------------------------------------

function RemoveFromOrgDialog({
  agentPid,
  agentName,
  onClose,
  onSuccess,
}: {
  agentPid: string;
  agentName: string;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const { t } = useI18n();
  const toast = useToastContext();
  const [submitting, setSubmitting] = useState(false);

  const handleConfirm = async () => {
    setSubmitting(true);
    try {
      const res = await del<void>(`/api/agent/definitions/${agentPid}/enroll-employee`);
      if (ResultHelper.isSuccess(res)) {
        toast.showSuccessToast(
          t(
            'ai.colleagues.removeOrg.success',
            { name: agentName },
            `${agentName} has been removed from the org chart`,
          ),
        );
        onSuccess();
      } else {
        toast.showErrorToast(
          t('ai.colleagues.removeOrg.error', undefined, 'Failed to remove from org'),
        );
      }
    } catch {
      toast.showErrorToast(
        t('ai.colleagues.removeOrg.error', undefined, 'Failed to remove from org'),
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
      data-testid="remove-org-dialog-overlay"
    >
      <div
        className="bg-panel dark:bg-subtle relative w-full max-w-sm rounded-2xl p-6 shadow-2xl"
        data-testid="remove-org-dialog"
      >
        <h2 className="text-text mb-2 text-base font-semibold dark:text-white">
          {t('ai.colleagues.removeOrg.title', undefined, 'Remove from Org Chart')}
        </h2>
        <p className="text-text-3 dark:text-text-3 mb-6 text-sm">
          {t(
            'ai.colleagues.removeOrg.confirm',
            { name: agentName },
            `This will deactivate ${agentName}'s employee record and remove them from the org chart. Continue?`,
          )}
        </p>
        <div className="flex justify-end gap-3">
          <button
            onClick={onClose}
            disabled={submitting}
            className="border-border-strong text-text-2 hover:bg-subtle dark:border-border-strong dark:text-text-3 dark:hover:bg-subtle rounded-lg border px-4 py-2 text-sm font-medium disabled:opacity-50"
          >
            {t('common.cancel', undefined, 'Cancel')}
          </button>
          <button
            onClick={handleConfirm}
            disabled={submitting}
            className="bg-status-red hover:bg-status-red rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
            data-testid="remove-org-confirm-btn"
          >
            {submitting
              ? t('ai.colleagues.removeOrg.removing', undefined, 'Removing...')
              : t('ai.colleagues.removeOrg.remove', undefined, 'Remove')}
          </button>
        </div>
      </div>
    </div>
  );
}

export function ReleasesTab({
  agentPid,
  agentUpdatedAt,
  readOnly,
}: {
  agentPid: string;
  agentUpdatedAt: string;
  readOnly: boolean;
}) {
  const { t } = useI18n();
  const { timezone, formats } = useTimezone();
  const toast = useToastContext();
  const [releases, setReleases] = useState<AgentReleaseItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [rollbackTarget, setRollbackTarget] = useState<AgentReleaseItem | null>(null);
  const [rollingBack, setRollingBack] = useState(false);
  const [deploymentPolicy, setDeploymentPolicy] = useState<AgentDeploymentPolicy | null>(null);
  const [policyLoading, setPolicyLoading] = useState(true);
  const [policyError, setPolicyError] = useState<string | null>(null);
  const [policySaving, setPolicySaving] = useState(false);

  const fetchReleases = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await get<AgentReleaseItem[]>(`/api/agent/definitions/${agentPid}/releases`);
      if (!ResultHelper.isSuccess(response)) {
        throw new Error('release history request failed');
      }
      setReleases(Array.isArray(response.data) ? response.data : []);
    } catch {
      setError(
        t(
          'ai.colleagues.releases.loadFailed',
          undefined,
          'Release history could not be loaded. Retry before publishing.',
        ),
      );
    } finally {
      setLoading(false);
    }
  }, [agentPid, t]);

  useEffect(() => {
    void fetchReleases();
  }, [fetchReleases]);

  const fetchDeploymentPolicy = useCallback(async () => {
    setPolicyLoading(true);
    setPolicyError(null);
    try {
      const response = await get<AgentDeploymentPolicy>(
        `/api/agent/definitions/${agentPid}/deployment-policy`,
      );
      if (!ResultHelper.isSuccess(response) || !response.data?.deploymentPid) {
        throw new Error('deployment policy request failed');
      }
      setDeploymentPolicy({
        ...response.data,
        channelPolicy: response.data.channelPolicy ?? {},
        policySnapshot: response.data.policySnapshot ?? {},
      });
    } catch {
      setDeploymentPolicy(null);
      setPolicyError(
        t(
          'ai.colleagues.policy.loadFailed',
          undefined,
          'Deployment policy could not be loaded. Runtime access remains unchanged.',
        ),
      );
    } finally {
      setPolicyLoading(false);
    }
  }, [agentPid, t]);

  useEffect(() => {
    void fetchDeploymentPolicy();
  }, [fetchDeploymentPolicy]);

  const updatePolicyArray = (
    field: keyof AgentDeploymentPolicy['channelPolicy'],
    value: string | number,
    checked: boolean,
  ) => {
    setDeploymentPolicy((current) => {
      if (!current) return current;
      const existing = (current.channelPolicy[field] as Array<string | number> | undefined) ?? [];
      const next = checked
        ? Array.from(new Set([...existing, value]))
        : existing.filter((item) => item !== value);
      return {
        ...current,
        channelPolicy: {
          ...current.channelPolicy,
          [field]: next,
        },
      };
    });
  };

  const parseIds = (value: string) =>
    Array.from(
      new Set(
        value
          .split(',')
          .map((item) => Number(item.trim()))
          .filter((item) => Number.isSafeInteger(item) && item > 0),
      ),
    );

  const saveDeploymentPolicy = async () => {
    if (!deploymentPolicy) return;
    setPolicySaving(true);
    try {
      const response = await put<AgentDeploymentPolicy>(
        `/api/agent/definitions/${agentPid}/deployment-policy`,
        deploymentPolicy.channelPolicy,
      );
      if (!ResultHelper.isSuccess(response) || !response.data?.deploymentPid) {
        throw new Error('deployment policy update failed');
      }
      setDeploymentPolicy(response.data);
      toast.showSuccessToast(
        t('ai.colleagues.policy.saved', undefined, 'Deployment invocation policy saved'),
      );
    } catch {
      toast.showErrorToast(
        t(
          'ai.colleagues.policy.saveFailed',
          undefined,
          'Policy save failed. The active deployment was not changed.',
        ),
      );
    } finally {
      setPolicySaving(false);
    }
  };

  const publish = async () => {
    setPublishing(true);
    try {
      const response = await post(`/api/agent/definitions/${agentPid}/publish`, {});
      if (!ResultHelper.isSuccess(response)) {
        throw new Error('publish failed');
      }
      toast.showSuccessToast(
        t(
          'ai.colleagues.releases.publishSuccess',
          undefined,
          'Immutable release published and deployed',
        ),
      );
      setConfirming(false);
      await fetchReleases();
    } catch {
      toast.showErrorToast(
        t(
          'ai.colleagues.releases.publishFailed',
          undefined,
          'Publish failed. The current deployment was not changed.',
        ),
      );
    } finally {
      setPublishing(false);
    }
  };

  const rollback = async () => {
    if (!rollbackTarget) return;
    setRollingBack(true);
    try {
      const response = await post(
        `/api/agent/definitions/${agentPid}/releases/${rollbackTarget.pid}/deploy`,
        {},
      );
      if (!ResultHelper.isSuccess(response)) {
        throw new Error('rollback failed');
      }
      toast.showSuccessToast(
        t(
          'ai.colleagues.releases.rollbackSuccess',
          { version: rollbackTarget.release_no },
          `Deployment rolled back to v${rollbackTarget.release_no}`,
        ),
      );
      setRollbackTarget(null);
      await fetchReleases();
    } catch {
      toast.showErrorToast(
        t(
          'ai.colleagues.releases.rollbackFailed',
          undefined,
          'Rollback failed. The current deployment was not changed.',
        ),
      );
    } finally {
      setRollingBack(false);
    }
  };

  const deployed = releases.find((release) => release.deployed);
  const unpublishedChanges =
    !deployed?.source_updated_at ||
    new Date(agentUpdatedAt).getTime() > new Date(deployed.source_updated_at).getTime();

  if (loading) {
    return (
      <div className="space-y-3" data-testid="agent-releases-loading">
        <div className="bg-subtle dark:bg-subtle h-20 animate-pulse rounded-xl" />
        <div className="bg-subtle dark:bg-subtle h-20 animate-pulse rounded-xl" />
      </div>
    );
  }

  if (error) {
    return (
      <div
        className="border-status-red bg-status-red-bg dark:border-status-red dark:bg-status-red-bg/30 rounded-xl border p-5"
        role="alert"
      >
        <p className="text-status-red dark:text-status-red text-sm">{error}</p>
        <button
          type="button"
          onClick={() => void fetchReleases()}
          className="border-status-red text-status-red hover:bg-status-red-bg dark:border-status-red dark:text-status-red mt-3 rounded-lg border px-3 py-1.5 text-sm font-medium"
        >
          {t('common.retry', undefined, 'Retry')}
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <div className="border-border bg-panel dark:border-border dark:bg-subtle flex flex-wrap items-start justify-between gap-4 rounded-xl border p-5">
        <div className="max-w-2xl">
          <h2 className="text-text font-semibold dark:text-white">
            {t('ai.colleagues.releases.title', undefined, 'Immutable runtime releases')}
          </h2>
          <p className="text-text-3 dark:text-text-3 mt-1 text-sm">
            {t(
              'ai.colleagues.releases.help',
              undefined,
              'Saving edits updates the draft only. Publishing creates a versioned snapshot; new turns use it while in-flight work keeps its pinned release.',
            )}
          </p>
          <p
            className={`mt-3 text-sm font-medium ${
              unpublishedChanges
                ? 'text-status-amber dark:text-status-amber'
                : 'text-status-green dark:text-status-green'
            }`}
            data-testid="agent-release-draft-state"
          >
            {unpublishedChanges
              ? t('ai.colleagues.releases.unpublished', undefined, 'Draft changes are not deployed')
              : t('ai.colleagues.releases.current', undefined, 'Deployment matches the draft')}
          </p>
        </div>
        {!readOnly && (
          <button
            type="button"
            onClick={() => setConfirming(true)}
            disabled={publishing}
            className="bg-accent hover:bg-accent-hover inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
            data-testid="publish-agent-release"
          >
            <RocketLaunchIcon className="h-4 w-4" />
            {t('ai.colleagues.releases.publish', undefined, 'Publish draft')}
          </button>
        )}
      </div>

      <section
        className="border-border bg-panel dark:border-border dark:bg-subtle rounded-xl border p-5"
        data-testid="agent-deployment-policy"
      >
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-text font-semibold dark:text-white">
              {t('ai.colleagues.policy.title', undefined, 'Deployment invocation policy')}
            </h2>
            <p className="text-text-3 dark:text-text-3 mt-1 text-sm">
              {t(
                'ai.colleagues.policy.help',
                undefined,
                'Restrict where this colleague can run and which initiators may invoke it. Empty groups keep tenant-compatible access.',
              )}
            </p>
          </div>
          {deploymentPolicy?.channelPolicy.version && (
            <span className="bg-subtle text-text-2 dark:bg-subtle dark:text-text-3 rounded-full px-2.5 py-1 font-mono text-xs">
              {deploymentPolicy.channelPolicy.version}
            </span>
          )}
        </div>

        {policyLoading ? (
          <div
            className="bg-subtle dark:bg-subtle mt-4 h-24 animate-pulse rounded-lg"
            data-testid="agent-deployment-policy-loading"
          />
        ) : policyError ? (
          <div
            className="border-status-red bg-status-red-bg mt-4 rounded-lg border p-4"
            role="alert"
          >
            <p className="text-status-red text-sm">{policyError}</p>
            <button
              type="button"
              onClick={() => void fetchDeploymentPolicy()}
              className="border-status-red text-status-red mt-2 rounded border px-3 py-1.5 text-sm"
              data-testid="retry-agent-deployment-policy"
            >
              {t('common.retry', undefined, 'Retry')}
            </button>
          </div>
        ) : deploymentPolicy ? (
          <div className="mt-5 space-y-5">
            <fieldset>
              <legend className="text-text dark:text-text-3 text-sm font-medium">
                {t('ai.colleagues.policy.channels', undefined, 'Allowed channels')}
              </legend>
              <div className="mt-2 flex flex-wrap gap-3">
                {DEPLOYMENT_CHANNELS.map((channel) => (
                  <label key={channel.value} className="inline-flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={(deploymentPolicy.channelPolicy.allowedChannels ?? []).includes(
                        channel.value,
                      )}
                      disabled={readOnly || policySaving}
                      onChange={(event) =>
                        updatePolicyArray('allowedChannels', channel.value, event.target.checked)
                      }
                      data-testid={`deployment-channel-${channel.value}`}
                    />
                    {t(channel.labelKey, undefined, channel.fallback)}
                  </label>
                ))}
              </div>
            </fieldset>

            <fieldset>
              <legend className="text-text dark:text-text-3 text-sm font-medium">
                {t('ai.colleagues.policy.initiators', undefined, 'Allowed initiator types')}
              </legend>
              <div className="mt-2 flex flex-wrap gap-3">
                {DEPLOYMENT_INITIATORS.map((kind) => (
                  <label key={kind.value} className="inline-flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={(
                        deploymentPolicy.channelPolicy.allowedInitiatorTypes ?? []
                      ).includes(kind.value)}
                      disabled={readOnly || policySaving}
                      onChange={(event) =>
                        updatePolicyArray('allowedInitiatorTypes', kind.value, event.target.checked)
                      }
                      data-testid={`deployment-initiator-${kind.value}`}
                    />
                    {t(kind.labelKey, undefined, kind.fallback)}
                  </label>
                ))}
              </div>
            </fieldset>

            <div className="grid gap-3 md:grid-cols-3">
              {[
                ['allowedUserIds', t('ai.colleagues.policy.allowedUserIds', undefined, 'User IDs')],
                [
                  'allowedMemberIds',
                  t('ai.colleagues.policy.allowedMemberIds', undefined, 'Member IDs'),
                ],
                ['allowedRoleIds', t('ai.colleagues.policy.allowedRoleIds', undefined, 'Role IDs')],
              ].map(([field, label]) => (
                <label key={field} className="text-text-2 dark:text-text-3 text-sm">
                  {label}
                  <input
                    type="text"
                    value={
                      (
                        deploymentPolicy.channelPolicy[
                          field as keyof AgentDeploymentPolicy['channelPolicy']
                        ] as number[] | undefined
                      )?.join(', ') ?? ''
                    }
                    disabled={readOnly || policySaving}
                    onChange={(event) =>
                      setDeploymentPolicy((current) =>
                        current
                          ? {
                              ...current,
                              channelPolicy: {
                                ...current.channelPolicy,
                                [field]: parseIds(event.target.value),
                              },
                            }
                          : current,
                      )
                    }
                    placeholder="101, 102"
                    className="border-border-strong dark:border-border dark:bg-subtle mt-1 w-full rounded-lg border px-3 py-2"
                    data-testid={`deployment-policy-${field}`}
                  />
                </label>
              ))}
            </div>

            {!readOnly && (
              <button
                type="button"
                onClick={() => void saveDeploymentPolicy()}
                disabled={policySaving}
                className="bg-accent hover:bg-accent-hover rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                data-testid="save-agent-deployment-policy"
              >
                {policySaving
                  ? t('common.saving', undefined, 'Saving...')
                  : t('common.save', undefined, 'Save policy')}
              </button>
            )}
          </div>
        ) : null}
      </section>

      {confirming && (
        <div
          className="border-accent bg-accent-weak dark:border-accent dark:bg-accent-weak/30 rounded-xl border p-5"
          role="dialog"
          aria-label={t(
            'ai.colleagues.releases.confirmTitle',
            undefined,
            'Publish immutable release',
          )}
        >
          <p className="text-text font-medium dark:text-white">
            {t('ai.colleagues.releases.confirmTitle', undefined, 'Publish immutable release')}
          </p>
          <p className="text-text-2 dark:text-text-3 mt-1 text-sm">
            {t(
              'ai.colleagues.releases.confirmBody',
              undefined,
              'New turns and runs will move to this snapshot. Existing work remains pinned to its original release.',
            )}
          </p>
          <div className="mt-4 flex gap-2">
            <button
              type="button"
              onClick={() => void publish()}
              disabled={publishing}
              className="bg-accent rounded-lg px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
              data-testid="confirm-publish-agent-release"
            >
              {publishing
                ? t('ai.colleagues.releases.publishing', undefined, 'Publishing...')
                : t('ai.colleagues.releases.confirm', undefined, 'Publish and deploy')}
            </button>
            <button
              type="button"
              onClick={() => setConfirming(false)}
              disabled={publishing}
              className="border-border-strong text-text-2 dark:border-border dark:text-text-3 rounded-lg border px-3 py-2 text-sm font-medium"
            >
              {t('common.cancel', undefined, 'Cancel')}
            </button>
          </div>
        </div>
      )}

      {rollbackTarget && (
        <div
          className="border-status-amber bg-status-amber-bg dark:border-status-amber dark:bg-status-amber-bg/30 rounded-xl border p-5"
          role="dialog"
          aria-label={t(
            'ai.colleagues.releases.rollbackTitle',
            undefined,
            'Roll back deployed release',
          )}
        >
          <p className="text-text font-medium dark:text-white">
            {t('ai.colleagues.releases.rollbackTitle', undefined, 'Roll back deployed release')}
          </p>
          <p className="text-text-2 dark:text-text-3 mt-1 text-sm">
            {t(
              'ai.colleagues.releases.rollbackBody',
              { version: rollbackTarget.release_no },
              `New work will use v${rollbackTarget.release_no}. In-flight work remains pinned to its original release.`,
            )}
          </p>
          <div className="mt-4 flex gap-2">
            <button
              type="button"
              onClick={() => void rollback()}
              disabled={rollingBack}
              className="bg-status-amber rounded-lg px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
              data-testid="confirm-rollback-agent-release"
            >
              {rollingBack
                ? t('ai.colleagues.releases.rollingBack', undefined, 'Rolling back...')
                : t('ai.colleagues.releases.rollbackConfirm', undefined, 'Roll back deployment')}
            </button>
            <button
              type="button"
              onClick={() => setRollbackTarget(null)}
              disabled={rollingBack}
              className="border-border-strong text-text-2 dark:border-border dark:text-text-3 rounded-lg border px-3 py-2 text-sm font-medium"
            >
              {t('common.cancel', undefined, 'Cancel')}
            </button>
          </div>
        </div>
      )}

      {releases.length === 0 ? (
        <div className="border-border-strong text-text-3 dark:border-border dark:text-text-3 rounded-xl border border-dashed p-8 text-center text-sm">
          {t(
            'ai.colleagues.releases.empty',
            undefined,
            'No release exists yet. Publish the draft before assigning work.',
          )}
        </div>
      ) : (
        <div className="space-y-3" data-testid="agent-release-history">
          {releases.map((release) => (
            <div
              key={release.pid}
              data-testid={`agent-release-${release.release_no}`}
              className="border-border bg-panel dark:border-border dark:bg-subtle flex flex-wrap items-center justify-between gap-3 rounded-xl border p-4"
            >
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-text font-semibold dark:text-white">
                    v{release.release_no}
                  </span>
                  {release.deployed && (
                    <span className="bg-status-green-bg text-status-green dark:bg-status-green-bg/40 dark:text-status-green rounded-full px-2 py-0.5 text-xs font-medium">
                      {t('ai.colleagues.releases.deployed', undefined, 'Deployed')}
                    </span>
                  )}
                  {!release.deployed && (
                    <span className="bg-subtle text-text-2 dark:bg-subtle dark:text-text-3 rounded-full px-2 py-0.5 text-xs">
                      {t('ai.colleagues.releases.historical', undefined, 'Historical')}
                    </span>
                  )}
                </div>
                <p className="text-text-3 dark:text-text-3 mt-1 font-mono text-xs">
                  {release.release_hash.slice(0, 12)} · {release.pid}
                </p>
              </div>
              <time className="text-text-3 dark:text-text-3 text-sm">
                {formatInTimezone(release.published_at, formats.datetime, timezone)}
              </time>
              {!readOnly && !release.deployed && (
                <button
                  type="button"
                  onClick={() => setRollbackTarget(release)}
                  disabled={rollingBack}
                  className="border-status-amber text-status-amber hover:bg-status-amber-bg dark:border-status-amber dark:text-status-amber dark:hover:bg-status-amber-bg/30 rounded-lg border px-3 py-1.5 text-sm font-medium disabled:opacity-50"
                  data-testid={`rollback-agent-release-${release.release_no}`}
                >
                  {t('ai.colleagues.releases.rollback', undefined, 'Deploy this version')}
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Page
// ---------------------------------------------------------------------------

export function AgentDetailTabs(_props?: { block?: unknown; runtime?: unknown }) {
  const { t } = useI18n();
  const [searchParams] = useSearchParams();
  const agentPid = searchParams.get('agentPid') || undefined;
  const navigate = useNavigate();
  const toast = useToastContext();
  const tabs = useTabs(t);

  const [agent, setAgent] = useState<AgentDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<TabKey>('profile');
  const [saving, setSaving] = useState(false);
  const [showEnrollDialog, setShowEnrollDialog] = useState(false);
  const [showRemoveOrgDialog, setShowRemoveOrgDialog] = useState(false);
  const [lifecycleBusy, setLifecycleBusy] = useState(false);
  const isSuspended = agent?.status === 'suspended';

  /**
   * Halt or release this one colleague. Refetches rather than flipping local
   * state, so what the page shows is what the server actually stored — a
   * button that recolours itself on click looks identical whether the write
   * landed or not.
   */
  const handleToggleSuspend = async () => {
    if (!agentPid) return;
    setLifecycleBusy(true);
    try {
      const action = isSuspended ? 'resume' : 'suspend';
      const res = await post(`/api/agent/definitions/${agentPid}/${action}`, {});
      if (ResultHelper.isSuccess(res)) {
        toast.showSuccessToast(
          isSuspended
            ? t('ai.colleagues.resume.success', undefined, 'Colleague resumed')
            : t(
                'ai.colleagues.suspend.success',
                undefined,
                'Colleague suspended — it will not take new work',
              ),
        );
        await fetchAgent();
      } else {
        toast.showErrorToast(
          t('ai.colleagues.suspend.failed', undefined, 'Could not change the colleague state'),
        );
      }
    } catch {
      toast.showErrorToast(
        t('ai.colleagues.suspend.failed', undefined, 'Could not change the colleague state'),
      );
    } finally {
      setLifecycleBusy(false);
    }
  };

  const isAuraBot = agent?.agent_code === AURABOT_CODE;
  const readOnly = isAuraBot;
  // Enrolment state comes from its own endpoint, not from the agent record. The dynamic-model
  // projection does not carry employee_id — and declaring it there would push an internal bigint
  // into the browser — so an enrolled colleague used to keep offering "Enroll as Employee", and a
  // second click answered with an error about system accounts that had nothing to do with the
  // real reason.
  const [placement, setPlacement] = useState<OrgPlacement | null>(null);
  const isEnrolled = !!placement?.enrolled;

  const fetchPlacement = useCallback(async () => {
    if (!agentPid) return;
    const res = await get<OrgPlacement>(`/api/agent/definitions/${agentPid}/org-placement`);
    if (ResultHelper.isSuccess(res) && res.data) setPlacement(res.data);
  }, [agentPid]);

  useEffect(() => {
    void fetchPlacement();
  }, [fetchPlacement]);

  const fetchAgent = useCallback(async () => {
    if (!agentPid) return;
    try {
      const res = await get<AgentDetail>(`/api/dynamic/agent-definition/${agentPid}`);
      if (ResultHelper.isSuccess(res) && res.data) {
        setAgent(res.data);
      } else {
        toast.showErrorToast(t('ai.colleagues.error.notFound', undefined, 'Agent not found'));
        navigate('/p/c/ai_colleagues');
      }
    } catch {
      toast.showErrorToast(t('ai.colleagues.error.loadFailed', undefined, 'Failed to load agent'));
    } finally {
      setLoading(false);
    }
  }, [agentPid, toast, t, navigate]);

  useEffect(() => {
    fetchAgent();
  }, [fetchAgent]);

  const handleSave = async (data: Partial<AgentDetail>) => {
    if (!agentPid || readOnly) return;
    setSaving(true);
    try {
      // PUT /{model}/{pid} is the route the platform actually maps. The old
      // POST .../update matched nothing, so every save on this page answered
      // 404 and was swallowed — the form kept the values on screen, the toast
      // said saved, and the record never changed. That is why clearing an
      // allowed operation appeared to work and then came back on reload.
      const res = await put(`/api/dynamic/agent-definition/${agentPid}`, data);
      if (ResultHelper.isSuccess(res)) {
        toast.showSuccessToast(
          t('ai.colleagues.success.saved', undefined, 'Agent saved successfully'),
        );
        fetchAgent();
      } else {
        toast.showErrorToast(
          t('ai.colleagues.error.saveFailed', undefined, 'Failed to save agent'),
        );
      }
    } catch {
      toast.showErrorToast(t('ai.colleagues.error.saveFailed', undefined, 'Failed to save agent'));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="mx-auto w-full max-w-5xl p-6">
        <div className="animate-pulse space-y-6">
          <div className="bg-border dark:bg-subtle h-8 w-48 rounded" />
          <div className="bg-border dark:bg-subtle h-10 w-full rounded" />
          <div className="bg-border dark:bg-subtle h-64 w-full rounded" />
        </div>
      </div>
    );
  }

  if (!agent) return null;

  return (
    <div className="mx-auto w-full max-w-5xl p-6">
      {/* Dialogs */}
      {showEnrollDialog && agentPid && (
        <EnrollEmployeeDialog
          agentPid={agentPid}
          agentName={agent.name}
          onClose={() => setShowEnrollDialog(false)}
          onSuccess={() => {
            setShowEnrollDialog(false);
            fetchAgent();
            void fetchPlacement();
          }}
        />
      )}
      {showRemoveOrgDialog && agentPid && (
        <RemoveFromOrgDialog
          agentPid={agentPid}
          agentName={agent.name}
          onClose={() => setShowRemoveOrgDialog(false)}
          onSuccess={() => {
            setShowRemoveOrgDialog(false);
            fetchAgent();
            void fetchPlacement();
          }}
        />
      )}

      {/* Back + Title */}
      <div className="mb-6 flex items-center gap-3">
        <button
          onClick={() => navigate('/p/c/ai_colleagues')}
          className="hover:bg-subtle dark:hover:bg-subtle rounded-lg p-2 transition-colors"
          data-testid="back-to-colleagues"
        >
          <ArrowLeftIcon className="text-text-2 dark:text-text-3 h-5 w-5" />
        </button>
        <div className="flex-1">
          <h1 className="text-text text-xl font-semibold dark:text-white">{agent.name}</h1>
          <p className="text-text-3 dark:text-text-3 text-sm">
            {agent.agent_code}
            {isAuraBot && (
              <span className="bg-accent ml-2 inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-semibold text-white">
                {t('ai.colleagues.badge.official', undefined, 'Official')}
              </span>
            )}
            {isSuspended && (
              <span
                className="bg-status-amber-bg text-status-amber dark:bg-status-amber-bg/40 dark:text-status-amber ml-2 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold"
                data-testid="agent-suspended-badge"
              >
                <PauseCircleIcon className="h-3 w-3" />
                {t('ai.colleagues.badge.suspended', undefined, 'Suspended — takes no new work')}
              </span>
            )}
            {isEnrolled && (
              <span
                className="bg-status-green-bg text-status-green dark:bg-status-green-bg/40 dark:text-status-green ml-2 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold"
                data-testid="digital-employee-badge"
              >
                <BuildingOfficeIcon className="h-3 w-3" />
                {/* Say where, not just that. "Digital Employee" alone leaves the reader to go and
                    look up which department it landed in. */}
                {[placement?.departmentName, placement?.positionName].filter(Boolean).join(' · ') ||
                  t('ai.colleagues.badge.employee', undefined, 'Digital Employee')}
              </span>
            )}
          </p>
        </div>

        {/* Lifecycle: stop this one colleague without silencing every agent in the
            deployment. Both engines resolve definitions with status='active', so
            suspending closes chat, dispatch and delegation at once. The backend
            could already do this; until now nothing in the interface could ask. */}
        {!isAuraBot && (
          <div className="ml-auto flex items-center gap-2">
            <button
              onClick={handleToggleSuspend}
              disabled={lifecycleBusy}
              className={
                isSuspended
                  ? 'border-status-green bg-panel text-status-green hover:bg-status-green-bg dark:border-status-green dark:bg-subtle dark:text-status-green inline-flex items-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium transition-colors disabled:opacity-50'
                  : 'border-status-amber bg-panel text-status-amber hover:bg-status-amber-bg dark:border-status-amber dark:bg-subtle dark:text-status-amber inline-flex items-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium transition-colors disabled:opacity-50'
              }
              data-testid={isSuspended ? 'agent-resume-btn' : 'agent-suspend-btn'}
            >
              {isSuspended ? (
                <PlayCircleIcon className="h-4 w-4" />
              ) : (
                <PauseCircleIcon className="h-4 w-4" />
              )}
              {isSuspended
                ? t('ai.colleagues.action.resume', undefined, 'Resume')
                : t('ai.colleagues.action.suspend', undefined, 'Suspend')}
            </button>
          </div>
        )}

        {/* Enrollment Actions — only for non-AuraBot agents that have a system user */}
        {/* Enrollment provisions the agent's backing system user on demand, so the button no
            longer waits for system_user_id — gating on it hid enrollment from every
            tenant-created agent, which never had one. */}
        {!isAuraBot && (
          <div>
            {isEnrolled ? (
              <button
                onClick={() => setShowRemoveOrgDialog(true)}
                className="border-status-red bg-panel text-status-red hover:bg-status-red-bg dark:border-status-red dark:bg-subtle dark:text-status-red dark:hover:bg-status-red-bg/30 inline-flex items-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium transition-colors"
                data-testid="remove-from-org-btn"
              >
                <BuildingOfficeIcon className="h-4 w-4" />
                {t('ai.colleagues.action.removeFromOrg', undefined, 'Remove from Org')}
              </button>
            ) : (
              <button
                onClick={() => setShowEnrollDialog(true)}
                className="bg-accent hover:bg-accent-hover inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium text-white transition-colors"
                data-testid="enroll-as-employee-btn"
              >
                <BuildingOfficeIcon className="h-4 w-4" />
                {t('ai.colleagues.action.enrollAsEmployee', undefined, 'Enroll as Employee')}
              </button>
            )}
          </div>
        )}
      </div>

      {/* Tabs */}
      <div className="border-border dark:border-border mb-6 border-b">
        <nav className="flex gap-6" aria-label="Tabs">
          {tabs.map((tab) => {
            const active = activeTab === tab.key;
            const Icon = tab.icon;
            return (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key)}
                className={`inline-flex items-center gap-1.5 border-b-2 pb-3 text-sm font-medium transition-colors ${
                  active
                    ? 'border-accent text-accent dark:border-accent dark:text-accent'
                    : 'text-text-3 hover:text-text-2 dark:text-text-3 dark:hover:text-text-3 border-transparent'
                }`}
                data-testid={`tab-${tab.key}`}
              >
                <Icon className="h-4 w-4" />
                {tab.label}
              </button>
            );
          })}
        </nav>
      </div>

      {/* Tab Content */}
      <div className="min-h-[400px]">
        {activeTab === 'profile' && (
          <ProfileTab agent={agent} readOnly={readOnly} onSave={handleSave} saving={saving} />
        )}
        {activeTab === 'tools' && agent && (
          <ToolsSkillsTab agent={agent} readOnly={readOnly} onSave={handleSave} saving={saving} />
        )}
        {activeTab === 'knowledge' && agent && (
          <KnowledgeBasesTab
            agent={agent}
            readOnly={readOnly}
            onSave={handleSave}
            saving={saving}
            onManageKnowledge={() => navigate('/aurabot/knowledge')}
          />
        )}
        {activeTab === 'memory' && agentPid && <MemoryTab agentPid={agentPid} />}
        {activeTab === 'releases' && agentPid && (
          <ReleasesTab agentPid={agentPid} agentUpdatedAt={agent.updated_at} readOnly={readOnly} />
        )}
        {activeTab === 'runs' && agent && <RunHistoryTab agentCode={agent.agent_code} />}
        {activeTab === 'schedules' && agent && <SchedulesTab agentCode={agent.agent_code} />}
      </div>
    </div>
  );
}

export default AgentDetailTabs;
