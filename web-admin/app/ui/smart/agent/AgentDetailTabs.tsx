/**
 * AI Colleague Detail — Tabbed Configuration Page
 *
 * 5 tabs: Profile, Tools & Skills, Memory, Run History, Schedules.
 * AuraBot (aurabot) renders in read-only mode.
 */

import { useState, useEffect, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router';
import {
  ArrowLeftIcon,
  UserCircleIcon,
  WrenchScrewdriverIcon,
  BookOpenIcon,
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
  XMarkIcon,
  ChevronDownIcon,
} from '@heroicons/react/24/outline';
import { get, post, put, del } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';

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
  allowed_models: string[] | string | null; // null or "*" = all, or ["crm_account","crm_lead"]
  allowed_operations: string[] | null; // ["query","create","update","delete","transition"]
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

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const AURABOT_CODE = 'aurabot';

type TabKey = 'profile' | 'tools' | 'memory' | 'runs' | 'schedules';

const AGENT_TYPES = ['reactive', 'copilot', 'autonomous', 'workflow'];
const COMM_STYLES = ['professional', 'friendly', 'concise', 'detailed'];

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
      key: 'memory' as TabKey,
      label: t('ai.colleagues.tab.memory', undefined, 'Memory'),
      icon: BookOpenIcon,
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
    ? 'bg-gray-50 dark:bg-gray-800 cursor-not-allowed'
    : 'bg-white dark:bg-gray-900';

  return (
    <div className="max-w-2xl space-y-6">
      {/* Basic Info */}
      <section>
        <h3 className="mb-3 text-sm font-semibold tracking-wide text-gray-900 uppercase dark:text-white">
          {t('ai.colleagues.section.basicInfo', undefined, 'Basic Information')}
        </h3>
        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('ai.colleagues.field.name', undefined, 'Name')} *
            </label>
            <input
              type="text"
              value={form.name}
              onChange={(e) => handleChange('name', e.target.value)}
              disabled={readOnly}
              className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
              data-testid="agent-name-input"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('ai.colleagues.field.description', undefined, 'Description')}
            </label>
            <textarea
              rows={3}
              value={form.description}
              onChange={(e) => handleChange('description', e.target.value)}
              disabled={readOnly}
              className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
              data-testid="agent-description-input"
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                {t('ai.colleagues.field.agentType', undefined, 'Agent Type')}
              </label>
              <select
                value={form.agent_type}
                onChange={(e) => handleChange('agent_type', e.target.value)}
                disabled={readOnly}
                className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
              >
                {AGENT_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                {t('ai.colleagues.field.model', undefined, 'Model')}
              </label>
              <input
                type="text"
                value={form.model}
                onChange={(e) => handleChange('model', e.target.value)}
                disabled={readOnly}
                className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
              />
            </div>
          </div>
        </div>
      </section>

      {/* Soul Profile */}
      <section>
        <h3 className="mb-3 text-sm font-semibold tracking-wide text-gray-900 uppercase dark:text-white">
          {t('ai.colleagues.section.soulProfile', undefined, 'Soul Profile')}
        </h3>
        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('ai.colleagues.field.personality', undefined, 'Personality')}
            </label>
            <textarea
              rows={2}
              value={form.personality}
              onChange={(e) => handleChange('personality', e.target.value)}
              disabled={readOnly}
              className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('ai.colleagues.field.expertise', undefined, 'Expertise')}
            </label>
            <textarea
              rows={2}
              value={form.expertise}
              onChange={(e) => handleChange('expertise', e.target.value)}
              disabled={readOnly}
              className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('ai.colleagues.field.communicationStyle', undefined, 'Communication Style')}
            </label>
            <select
              value={form.communication_style}
              onChange={(e) => handleChange('communication_style', e.target.value)}
              disabled={readOnly}
              className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
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
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('ai.colleagues.field.boundaries', undefined, 'Boundaries')}
            </label>
            <textarea
              rows={2}
              value={form.boundaries}
              onChange={(e) => handleChange('boundaries', e.target.value)}
              disabled={readOnly}
              className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('ai.colleagues.field.systemPrompt', undefined, 'System Prompt')}
            </label>
            <textarea
              rows={6}
              value={form.system_prompt}
              onChange={(e) => handleChange('system_prompt', e.target.value)}
              disabled={readOnly}
              className={`w-full rounded-lg border border-gray-300 px-3 py-2 font-mono text-sm text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
            />
          </div>
        </div>
      </section>

      {/* Execution Limits */}
      <section>
        <h3 className="mb-3 text-sm font-semibold tracking-wide text-gray-900 uppercase dark:text-white">
          {t('ai.colleagues.section.executionLimits', undefined, 'Execution Limits')}
        </h3>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('ai.colleagues.field.maxConcurrentRuns', undefined, 'Max Concurrent Runs')}
            </label>
            <input
              type="number"
              min={1}
              max={10}
              value={form.max_concurrent_runs}
              onChange={(e) => handleChange('max_concurrent_runs', parseInt(e.target.value) || 1)}
              disabled={readOnly}
              className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
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
              className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-gray-900 dark:border-gray-600 dark:text-white ${fieldClass} transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500`}
            />
          </div>
        </div>
      </section>

      {/* Visibility / Sharing */}
      <section>
        <h3 className="mb-1 text-sm font-semibold tracking-wide text-gray-900 uppercase dark:text-white">
          {t('ai.colleagues.section.visibility', undefined, 'Visibility & Sharing')}
        </h3>
        <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
          {t(
            'ai.colleagues.section.visibilityDesc',
            undefined,
            'Control who can see and use this AI colleague.',
          )}
        </p>
        {readOnly ? (
          <div className="flex items-center gap-2 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2 dark:border-blue-800 dark:bg-blue-900/10">
            <GlobeAltIcon className="h-4 w-4 flex-shrink-0 text-blue-500" />
            <span className="text-sm font-medium text-blue-700 dark:text-blue-300">
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
                  color: 'border-gray-200 dark:border-gray-700',
                  activeColor:
                    'border-gray-400 bg-gray-50 dark:border-gray-500 dark:bg-gray-800/50',
                  iconColor: 'text-gray-500',
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
                  color: 'border-gray-200 dark:border-gray-700',
                  activeColor:
                    'border-purple-300 bg-purple-50 dark:border-purple-700 dark:bg-purple-900/10',
                  iconColor: 'text-purple-500',
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
                  color: 'border-gray-200 dark:border-gray-700',
                  activeColor:
                    'border-blue-300 bg-blue-50 dark:border-blue-700 dark:bg-blue-900/10',
                  iconColor: 'text-blue-500',
                },
              ] as const
            ).map(({ value, Icon, label, desc, color, activeColor, iconColor }) => {
              const selected = form.visibility === value;
              return (
                <label
                  key={value}
                  className={`flex cursor-pointer items-center gap-3 rounded-lg border p-3 transition-colors ${selected ? activeColor : color} hover:border-blue-200 dark:hover:border-blue-700`}
                  data-testid={`visibility-option-${value}`}
                >
                  <input
                    type="radio"
                    name="visibility"
                    value={value}
                    checked={selected}
                    onChange={() => handleChange('visibility', value)}
                    className="h-4 w-4 border-gray-300 text-blue-600 focus:ring-blue-500"
                  />
                  <Icon className={`h-4 w-4 flex-shrink-0 ${iconColor}`} />
                  <div>
                    <span className="text-sm font-medium text-gray-900 dark:text-white">
                      {label}
                    </span>
                    <span className="block text-xs text-gray-500 dark:text-gray-400">{desc}</span>
                  </div>
                </label>
              );
            })}
          </div>
        )}
      </section>

      {/* Save button */}
      {!readOnly && (
        <div className="border-t border-gray-200 pt-4 dark:border-gray-700">
          <button
            onClick={() => onSave(form)}
            disabled={saving}
            className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
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
        <div key={i} className="h-10 animate-pulse rounded bg-gray-100 dark:bg-gray-800" />
      ))}
    </div>
  );

  const isAuraBot = agent.agent_code === AURABOT_CODE;

  return (
    <div className="max-w-4xl space-y-8">
      {/* AuraBot full-access banner */}
      {isAuraBot && (
        <div
          className="flex items-start gap-3 rounded-lg border border-blue-200 bg-blue-50 p-4 dark:border-blue-800 dark:bg-blue-900/20"
          data-testid="aurabot-full-access-banner"
        >
          <ShieldCheckIcon className="mt-0.5 h-5 w-5 flex-shrink-0 text-blue-600 dark:text-blue-400" />
          <div>
            <p className="text-sm font-medium text-blue-800 dark:text-blue-200">
              {t('ai.colleagues.scope.fullAccess', undefined, 'Full Access Agent')}
            </p>
            <p className="mt-0.5 text-sm text-blue-600 dark:text-blue-300">
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
        <h3 className="mb-1 text-sm font-semibold tracking-wide text-gray-900 uppercase dark:text-white">
          {t('ai.colleagues.scope.modelAccess', undefined, 'Data Model Access')}
        </h3>
        <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
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
              ? 'border-blue-300 bg-blue-50 dark:border-blue-700 dark:bg-blue-900/20'
              : 'border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-900'
          } ${readOnly ? 'cursor-not-allowed opacity-70' : 'cursor-pointer hover:border-blue-300 dark:hover:border-blue-700'}`}
          data-testid="all-models-toggle"
        >
          <input
            type="checkbox"
            checked={allAccess}
            onChange={toggleAllAccess}
            disabled={readOnly}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
          />
          <div>
            <span className="text-sm font-medium text-gray-900 dark:text-white">
              {t('ai.colleagues.scope.allModels', undefined, 'Access all models')}
            </span>
            <span className="block text-xs text-gray-500 dark:text-gray-400">
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
                      className="overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700"
                    >
                      {/* Group header */}
                      <label
                        className={`flex items-center gap-3 bg-gray-50 px-4 py-2.5 dark:bg-gray-800/50 ${readOnly ? 'cursor-not-allowed' : 'cursor-pointer'}`}
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
                          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
                        />
                        <span className="text-sm font-semibold text-gray-700 dark:text-gray-200">
                          {group}
                        </span>
                        <span className="ml-auto text-xs text-gray-400 dark:text-gray-500">
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
                                className="h-3.5 w-3.5 rounded border-gray-300 text-blue-600 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
                              />
                              <span
                                className="truncate text-sm text-gray-700 dark:text-gray-300"
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
          <div className="flex items-center gap-2 px-1 text-sm text-gray-500 dark:text-gray-400">
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
        <h3 className="mb-1 text-sm font-semibold tracking-wide text-gray-900 uppercase dark:text-white">
          {t('ai.colleagues.scope.operations', undefined, 'Operation Permissions')}
        </h3>
        <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
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
                    ? 'border-blue-200 bg-blue-50/50 dark:border-blue-800 dark:bg-blue-900/10'
                    : 'border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-900'
                } ${readOnly ? 'cursor-not-allowed opacity-70' : 'cursor-pointer hover:border-blue-200 dark:hover:border-blue-700'}`}
                data-testid={`op-toggle-${op}`}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggleOp(op)}
                  disabled={readOnly}
                  className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
                />
                <div>
                  <span className="text-sm font-medium text-gray-900 dark:text-white">
                    {meta.label}
                  </span>
                  <span className="block text-xs text-gray-500 dark:text-gray-400">
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
        <div className="border-t border-gray-200 pt-4 dark:border-gray-700">
          <button
            onClick={handleSaveScope}
            disabled={saving}
            className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
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
        <h3 className="mb-3 text-sm font-semibold tracking-wide text-gray-900 uppercase dark:text-white">
          {t('ai.colleagues.tools.available', undefined, 'Available Tools')}
        </h3>
        {loadingTools ? (
          skeleton
        ) : tools.length === 0 ? (
          <div className="py-8 text-center text-gray-400 dark:text-gray-500">
            <WrenchScrewdriverIcon className="mx-auto mb-2 h-10 w-10 text-gray-300 dark:text-gray-600" />
            <p className="text-sm">
              {t('ai.colleagues.tools.noTools', undefined, 'No tools configured')}
            </p>
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-gray-500 dark:text-gray-400">
                <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
                  Code
                </th>
                <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
                  Name
                </th>
                <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
                  Type
                </th>
              </tr>
            </thead>
            <tbody>
              {tools.map((row) => (
                <tr
                  key={row.pid}
                  className="border-b border-gray-100 hover:bg-gray-50 dark:border-gray-800 dark:hover:bg-gray-800/50"
                >
                  <td className="p-2 font-mono text-xs text-gray-700 dark:text-gray-300">
                    {row.tool_code}
                  </td>
                  <td className="p-2 text-gray-900 dark:text-white">{row.tool_name}</td>
                  <td className="p-2">
                    <span className="inline-flex rounded-full bg-gray-100 px-2 py-0.5 text-[11px] font-medium text-gray-600 dark:bg-gray-800 dark:text-gray-400">
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
        <h3 className="mb-3 text-sm font-semibold tracking-wide text-gray-900 uppercase dark:text-white">
          {t('ai.colleagues.tools.skills', undefined, 'Skills')}
        </h3>
        {loadingSkills ? (
          skeleton
        ) : skills.length === 0 ? (
          <div className="py-8 text-center text-gray-400 dark:text-gray-500">
            <SparklesIcon className="mx-auto mb-2 h-10 w-10 text-gray-300 dark:text-gray-600" />
            <p className="text-sm">
              {t('ai.colleagues.tools.noSkills', undefined, 'No skills configured')}
            </p>
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-gray-500 dark:text-gray-400">
                <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
                  Code
                </th>
                <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
                  Name
                </th>
                <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
                  Category
                </th>
                <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
                  Execution Mode
                </th>
              </tr>
            </thead>
            <tbody>
              {skills.map((row) => (
                <tr
                  key={row.pid}
                  className="border-b border-gray-100 hover:bg-gray-50 dark:border-gray-800 dark:hover:bg-gray-800/50"
                >
                  <td className="p-2 font-mono text-xs text-gray-700 dark:text-gray-300">
                    {row.skill_code}
                  </td>
                  <td className="p-2 text-gray-900 dark:text-white">{row.skill_name}</td>
                  <td className="p-2 text-gray-600 dark:text-gray-400">
                    {row.skill_category ?? '-'}
                  </td>
                  <td className="p-2">
                    <span className="inline-flex rounded-full bg-blue-100 px-2 py-0.5 text-[11px] font-medium text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
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
          <div key={i} className="h-12 animate-pulse rounded-lg bg-gray-100 dark:bg-gray-800" />
        ))}
      </div>
    );
  }

  if (runs.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <ClockIcon className="mb-3 h-12 w-12 text-gray-300 dark:text-gray-600" />
        <h3 className="text-lg font-medium text-gray-900 dark:text-white">
          {t('ai.colleagues.runs.empty', undefined, 'No runs yet')}
        </h3>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
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
        return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300';
      case 'running':
        return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300';
      case 'failed':
        return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300';
      default:
        return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400';
    }
  };

  const formatDuration = (ms: number | null) => {
    if (ms == null) return '-';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  return (
    <div className="max-w-4xl">
      <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
        {t('ai.colleagues.runs.count', { count: runs.length }, `${runs.length} recent runs`)}
      </p>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-gray-500 dark:text-gray-400">
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">Task</th>
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
              Status
            </th>
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">Model</th>
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
              Duration
            </th>
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
              Tokens
            </th>
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
              Started
            </th>
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => (
            <tr
              key={run.pid}
              className="border-b border-gray-100 hover:bg-gray-50 dark:border-gray-800 dark:hover:bg-gray-800/50"
            >
              <td className="max-w-[200px] truncate p-2 text-gray-900 dark:text-white">
                {run.task_title ?? '-'}
              </td>
              <td className="p-2">
                <span
                  className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium ${statusColor(run.run_status)}`}
                >
                  {run.run_status}
                </span>
              </td>
              <td className="p-2 font-mono text-xs text-gray-600 dark:text-gray-400">
                {run.model ?? '-'}
              </td>
              <td className="p-2 text-gray-600 dark:text-gray-400">
                {formatDuration(run.duration_ms)}
              </td>
              <td className="p-2 text-gray-600 dark:text-gray-400">
                {run.input_tokens || run.output_tokens
                  ? `${run.input_tokens ?? 0} / ${run.output_tokens ?? 0}`
                  : '-'}
              </td>
              <td className="p-2 text-xs text-gray-500 dark:text-gray-400">
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
  schedule_name: string;
  cron_expression: string | null;
  schedule_status: string;
  next_run_at: string | null;
  last_run_at: string | null;
}

function SchedulesTab({ agentCode }: { agentCode: string }) {
  const { t } = useI18n();
  const [schedules, setSchedules] = useState<ScheduleRecord[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    (async () => {
      try {
        const filters = JSON.stringify([
          { fieldName: 'agent_code', operator: 'eq', value: agentCode },
        ]);
        const res = await get<{ records: ScheduleRecord[] }>('/api/dynamic/agent-schedule/list', {
          pageNum: 1,
          pageSize: 20,
          filters,
        });
        if (ResultHelper.isSuccess(res) && res.data?.records) {
          setSchedules(res.data.records);
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
          <div key={i} className="h-12 animate-pulse rounded-lg bg-gray-100 dark:bg-gray-800" />
        ))}
      </div>
    );
  }

  if (schedules.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <CalendarDaysIcon className="mb-3 h-12 w-12 text-gray-300 dark:text-gray-600" />
        <h3 className="text-lg font-medium text-gray-900 dark:text-white">
          {t('ai.colleagues.schedules.empty', undefined, 'No schedules')}
        </h3>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          {t(
            'ai.colleagues.schedules.emptyDesc',
            undefined,
            'Scheduled tasks will appear here once configured.',
          )}
        </p>
      </div>
    );
  }

  const statusColor = (s: string) => {
    switch (s?.toLowerCase()) {
      case 'active':
        return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300';
      case 'paused':
        return 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300';
      case 'disabled':
        return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400';
      default:
        return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400';
    }
  };

  return (
    <div className="max-w-4xl">
      <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
        {t(
          'ai.colleagues.schedules.count',
          { count: schedules.length },
          `${schedules.length} schedules`,
        )}
      </p>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-gray-500 dark:text-gray-400">
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">Name</th>
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">Cron</th>
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
              Status
            </th>
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
              Next Run
            </th>
            <th className="border-b border-gray-200 p-2 font-medium dark:border-gray-700">
              Last Run
            </th>
          </tr>
        </thead>
        <tbody>
          {schedules.map((row) => (
            <tr
              key={row.pid}
              className="border-b border-gray-100 hover:bg-gray-50 dark:border-gray-800 dark:hover:bg-gray-800/50"
            >
              <td className="p-2 text-gray-900 dark:text-white">{row.schedule_name}</td>
              <td className="p-2 font-mono text-xs text-gray-600 dark:text-gray-400">
                {row.cron_expression ?? '-'}
              </td>
              <td className="p-2">
                <span
                  className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium ${statusColor(row.schedule_status)}`}
                >
                  {row.schedule_status}
                </span>
              </td>
              <td className="p-2 text-xs text-gray-500 dark:text-gray-400">
                {row.next_run_at ? new Date(row.next_run_at).toLocaleString() : '-'}
              </td>
              <td className="p-2 text-xs text-gray-500 dark:text-gray-400">
                {row.last_run_at ? new Date(row.last_run_at).toLocaleString() : '-'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
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
          <div key={i} className="h-20 animate-pulse rounded-lg bg-gray-100 dark:bg-gray-800" />
        ))}
      </div>
    );
  }

  if (memories.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <BookOpenIcon className="mb-3 h-12 w-12 text-gray-300 dark:text-gray-600" />
        <h3 className="text-lg font-medium text-gray-900 dark:text-white">
          {t('ai.colleagues.memory.empty', undefined, 'No memories yet')}
        </h3>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
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
      <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
        {t('ai.colleagues.memory.count', { count: memories.length }, `${memories.length} memories`)}
      </p>
      {memories.map((mem) => (
        <div
          key={mem.pid}
          className="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-900"
        >
          <div className="mb-2 flex items-center gap-2">
            <span className="inline-flex rounded-full bg-blue-100 px-2 py-0.5 text-[11px] font-medium text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
              {mem.memory_type}
            </span>
            {mem.category && (
              <span className="inline-flex rounded-full bg-gray-100 px-2 py-0.5 text-[11px] font-medium text-gray-600 dark:bg-gray-800 dark:text-gray-400">
                {mem.category}
              </span>
            )}
            <span className="ml-auto text-xs text-gray-400">
              {new Date(mem.created_at).toLocaleDateString()}
            </span>
          </div>
          {mem.memory_title && (
            <h4 className="mb-1 text-sm font-medium text-gray-900 dark:text-white">
              {mem.memory_title}
            </h4>
          )}
          <p className="line-clamp-4 text-sm whitespace-pre-wrap text-gray-600 dark:text-gray-300">
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

function flattenDepts(nodes: DepartmentTreeNode[], depth = 0): { pid: string; name: string; depth: number }[] {
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
  const [departments, setDepartments] = useState<{ pid: string; name: string; depth: number }[]>([]);
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
      params: { pageSize: 200, filters: JSON.stringify([{ fieldName: 'org_pos_dept_id', operator: 'EQ', value: selectedDeptPid }]) },
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
      toast.showErrorToast(t('ai.colleagues.enroll.error.deptRequired', undefined, 'Please select a department'));
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
          t('ai.colleagues.enroll.success', { name: agentName }, `${agentName} has been enrolled as a digital employee`),
        );
        onSuccess();
      } else {
        toast.showErrorToast(
          t('ai.colleagues.enroll.error.failed', undefined, 'Enrollment failed. Ensure the agent has a system account.'),
        );
      }
    } catch {
      toast.showErrorToast(t('ai.colleagues.enroll.error.failed', undefined, 'Enrollment failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const selectClass =
    'w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 appearance-none dark:border-gray-600 dark:bg-gray-900 dark:text-white focus:border-blue-500 focus:ring-2 focus:ring-blue-500 transition-colors disabled:opacity-50';

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
      data-testid="enroll-dialog-overlay"
    >
      <div
        className="relative w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl dark:bg-gray-900"
        data-testid="enroll-dialog"
      >
        {/* Header */}
        <div className="mb-5 flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-blue-100 dark:bg-blue-900/40">
              <BuildingOfficeIcon className="h-5 w-5 text-blue-600 dark:text-blue-400" />
            </div>
            <div>
              <h2 className="text-base font-semibold text-gray-900 dark:text-white">
                {t('ai.colleagues.enroll.title', undefined, 'Enroll as Employee')}
              </h2>
              <p className="text-xs text-gray-500 dark:text-gray-400">
                {t('ai.colleagues.enroll.subtitle', { name: agentName }, `Add ${agentName} to the org chart`)}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-800"
          >
            <XMarkIcon className="h-5 w-5" />
          </button>
        </div>

        {/* Department */}
        <div className="mb-4">
          <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
            {t('ai.colleagues.enroll.field.department', undefined, 'Department')} *
          </label>
          {loadingDepts ? (
            <div className="h-9 w-full animate-pulse rounded-lg bg-gray-100 dark:bg-gray-800" />
          ) : departments.length === 0 ? (
            /* A required field with nothing selectable is a dead end: the only
               feedback used to be "Please select a department" on submit, for a
               list that had none to offer. Say what is missing and where to fix it. */
            <div
              className="rounded-lg border border-dashed border-gray-300 px-3 py-2.5 text-sm text-gray-500 dark:border-gray-600 dark:text-gray-400"
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
                onChange={(e) => { setSelectedDeptPid(e.target.value); setSelectedPosPid(''); }}
                className={selectClass}
                data-testid="enroll-dept-select"
              >
                <option value="">
                  {t('ai.colleagues.enroll.placeholder.department', undefined, '— Select Department —')}
                </option>
                {departments.map((d) => (
                  <option key={d.pid} value={d.pid}>
                    {'  '.repeat(d.depth)}{d.depth > 0 ? '└ ' : ''}{d.name}
                  </option>
                ))}
              </select>
              <ChevronDownIcon className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
            </div>
          )}
        </div>

        {/* Position */}
        <div className="mb-6">
          <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
            {t('ai.colleagues.enroll.field.position', undefined, 'Position')} *
            <span className="ml-1 text-xs text-gray-400">
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
                  : t('ai.colleagues.enroll.placeholder.position', undefined, '— Select Position —')}
              </option>
              {positions.map((p) => (
                <option key={p.pid} value={p.pid}>
                  {p.org_pos_name}
                </option>
              ))}
            </select>
            <ChevronDownIcon className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          </div>
          {!selectedDeptPid && (
            <p className="mt-1 text-xs text-gray-400">
              {t('ai.colleagues.enroll.hint.selectDeptFirst', undefined, 'Select a department first to load positions')}
            </p>
          )}
          {selectedDeptPid && !loadingPos && positions.length === 0 && (
            <p className="mt-1 text-xs text-amber-600 dark:text-amber-500" data-testid="enroll-position-empty">
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
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-800"
          >
            {t('common.cancel', undefined, 'Cancel')}
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting || !selectedDeptPid || !selectedPosPid}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
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
          t('ai.colleagues.removeOrg.success', { name: agentName }, `${agentName} has been removed from the org chart`),
        );
        onSuccess();
      } else {
        toast.showErrorToast(t('ai.colleagues.removeOrg.error', undefined, 'Failed to remove from org'));
      }
    } catch {
      toast.showErrorToast(t('ai.colleagues.removeOrg.error', undefined, 'Failed to remove from org'));
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
        className="relative w-full max-w-sm rounded-2xl bg-white p-6 shadow-2xl dark:bg-gray-900"
        data-testid="remove-org-dialog"
      >
        <h2 className="mb-2 text-base font-semibold text-gray-900 dark:text-white">
          {t('ai.colleagues.removeOrg.title', undefined, 'Remove from Org Chart')}
        </h2>
        <p className="mb-6 text-sm text-gray-500 dark:text-gray-400">
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
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-800"
          >
            {t('common.cancel', undefined, 'Cancel')}
          </button>
          <button
            onClick={handleConfirm}
            disabled={submitting}
            className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
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
            : t('ai.colleagues.suspend.success', undefined, 'Colleague suspended — it will not take new work'),
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
          <div className="h-8 w-48 rounded bg-gray-200 dark:bg-gray-700" />
          <div className="h-10 w-full rounded bg-gray-200 dark:bg-gray-700" />
          <div className="h-64 w-full rounded bg-gray-200 dark:bg-gray-700" />
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
          onSuccess={() => { setShowEnrollDialog(false); fetchAgent(); void fetchPlacement(); }}
        />
      )}
      {showRemoveOrgDialog && agentPid && (
        <RemoveFromOrgDialog
          agentPid={agentPid}
          agentName={agent.name}
          onClose={() => setShowRemoveOrgDialog(false)}
          onSuccess={() => { setShowRemoveOrgDialog(false); fetchAgent(); void fetchPlacement(); }}
        />
      )}

      {/* Back + Title */}
      <div className="mb-6 flex items-center gap-3">
        <button
          onClick={() => navigate('/p/c/ai_colleagues')}
          className="rounded-lg p-2 transition-colors hover:bg-gray-100 dark:hover:bg-gray-800"
          data-testid="back-to-colleagues"
        >
          <ArrowLeftIcon className="h-5 w-5 text-gray-600 dark:text-gray-300" />
        </button>
        <div className="flex-1">
          <h1 className="text-xl font-semibold text-gray-900 dark:text-white">{agent.name}</h1>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {agent.agent_code}
            {isAuraBot && (
              <span className="ml-2 inline-flex items-center rounded-full bg-blue-600 px-2 py-0.5 text-[11px] font-semibold text-white">
                {t('ai.colleagues.badge.official', undefined, 'Official')}
              </span>
            )}
            {isSuspended && (
              <span
                className="ml-2 inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-700 dark:bg-amber-900/40 dark:text-amber-400"
                data-testid="agent-suspended-badge"
              >
                <PauseCircleIcon className="h-3 w-3" />
                {t('ai.colleagues.badge.suspended', undefined, 'Suspended — takes no new work')}
              </span>
            )}
            {isEnrolled && (
              <span
                className="ml-2 inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-[11px] font-semibold text-green-700 dark:bg-green-900/40 dark:text-green-400"
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
                  ? 'inline-flex items-center gap-2 rounded-lg border border-green-200 bg-white px-3 py-2 text-sm font-medium text-green-700 transition-colors hover:bg-green-50 disabled:opacity-50 dark:border-green-800 dark:bg-gray-900 dark:text-green-400'
                  : 'inline-flex items-center gap-2 rounded-lg border border-amber-200 bg-white px-3 py-2 text-sm font-medium text-amber-700 transition-colors hover:bg-amber-50 disabled:opacity-50 dark:border-amber-800 dark:bg-gray-900 dark:text-amber-400'
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
                className="inline-flex items-center gap-2 rounded-lg border border-red-200 bg-white px-3 py-2 text-sm font-medium text-red-600 transition-colors hover:bg-red-50 dark:border-red-800 dark:bg-gray-900 dark:text-red-400 dark:hover:bg-red-950/30"
                data-testid="remove-from-org-btn"
              >
                <BuildingOfficeIcon className="h-4 w-4" />
                {t('ai.colleagues.action.removeFromOrg', undefined, 'Remove from Org')}
              </button>
            ) : (
              <button
                onClick={() => setShowEnrollDialog(true)}
                className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-3 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
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
      <div className="mb-6 border-b border-gray-200 dark:border-gray-700">
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
                    ? 'border-blue-600 text-blue-600 dark:border-blue-400 dark:text-blue-400'
                    : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
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
        {activeTab === 'memory' && agentPid && <MemoryTab agentPid={agentPid} />}
        {activeTab === 'runs' && agent && <RunHistoryTab agentCode={agent.agent_code} />}
        {activeTab === 'schedules' && agent && <SchedulesTab agentCode={agent.agent_code} />}
      </div>
    </div>
  );
}

export default AgentDetailTabs;
