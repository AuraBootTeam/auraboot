/**
 * AI Colleague Creation Wizard — template selection + 3-step guided flow
 *
 * Step 0 (template picker): Choose a pre-built template or start from scratch
 * Step 1: Identity (name, description, avatar, agent type)
 * Step 2: Personality (role description, communication style, system prompt)
 * Step 3: Review & Create (summary + submit)
 */

import { useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router';
import {
  ArrowLeftIcon,
  ArrowRightIcon,
  CheckIcon,
  SparklesIcon,
  UserCircleIcon,
  ChatBubbleLeftIcon,
  ClipboardDocumentCheckIcon,
  ChevronDownIcon,
  ChevronUpIcon,
  CpuChipIcon,
} from '@heroicons/react/24/outline';
import { get, post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Agent code
// ---------------------------------------------------------------------------

/**
 * Builds the `agent_code` the backend requires on create.
 *
 * The wizard collects a display name, but `agent_code` is a separate NOT NULL column with a
 * `(tenant_id, agent_code)` unique index, so it cannot simply be the name. A name is also not
 * guaranteed to survive slugging — a purely non-ASCII name ("小艾") slugs to the empty string —
 * hence the `agent` fallback rather than an empty prefix.
 *
 * The suffix is supplied by the caller so this stays pure and testable; callers pass a
 * time-derived value, which is what keeps two colleagues created from the same template apart.
 */
export function deriveAgentCode(name: string, uniqueSuffix: string): string {
  const slug = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 60);
  return `${slug || 'agent'}_${uniqueSuffix}`;
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface WizardData {
  name: string;
  description: string;
  avatarIcon: string;
  agent_type: string;
  personality: string;
  communication_style: string;
  system_prompt: string;
  /** Provider code the colleague talks through, e.g. "qianwen". See ConfiguredProvider. */
  provider: string;
}

/** An LLM service the tenant has actually configured a key for. */
interface ConfiguredProvider {
  providerCode: string;
  displayName: string;
}

interface AgentTemplate {
  id: string;
  icon: string;
  nameKey: string;
  descriptionKey: string;
  /**
   * A template describes a role — persona, tone, prompt. It deliberately does not carry a
   * provider: which AI service the tenant has a key for is a property of the tenant, not of
   * "procurement approver", and a template that pinned one would recreate the dead-default bug
   * the moment it named a vendor this tenant does not use.
   */
  defaults: Omit<WizardData, 'avatarIcon' | 'provider'>;
}

// ---------------------------------------------------------------------------
// Template Definitions (hardcoded presets — not DB records)
// ---------------------------------------------------------------------------

const AGENT_TEMPLATES: AgentTemplate[] = [
  {
    id: 'procurement_approver',
    icon: '📋',
    nameKey: 'ai.template.procurement.name',
    descriptionKey: 'ai.template.procurement.desc',
    defaults: {
      name: 'Procurement Approver',
      description: 'Reviews purchase orders, checks budgets, and follows approval SOP',
      agent_type: 'reactive',
      personality:
        'Meticulous, detail-oriented, follows rules strictly. You verify data before making decisions and always provide clear reasoning for approvals or rejections.',
      communication_style: 'professional',
      system_prompt:
        'You are a procurement approval specialist. When reviewing purchase orders:\n1. Check the supplier history and rating\n2. Verify budget availability for the department\n3. If amount < 5000 and supplier rating is A, recommend approval\n4. Otherwise, flag for manual review with your detailed analysis\nAlways cite specific data points in your recommendations.',
    },
  },
  {
    id: 'weekly_report_writer',
    icon: '📊',
    nameKey: 'ai.template.weeklyReport.name',
    descriptionKey: 'ai.template.weeklyReport.desc',
    defaults: {
      name: 'Weekly Report Writer',
      description: 'Generates team weekly reports from activity data and project updates',
      agent_type: 'autonomous',
      personality:
        'Organized, concise, and thorough. You synthesize information from multiple sources into clear, structured reports that highlight progress, blockers, and next steps.',
      communication_style: 'professional',
      system_prompt:
        'You are a weekly report generation assistant. Your task:\n1. Collect completed tasks and milestones from the past week\n2. Identify ongoing issues and blockers\n3. Summarize key metrics and KPIs\n4. Draft a concise weekly report with sections: Highlights, Progress, Blockers, Next Week Plan\nKeep the report under 500 words and use bullet points for clarity.',
    },
  },
  {
    id: 'customer_service',
    icon: '🎧',
    nameKey: 'ai.template.customerService.name',
    descriptionKey: 'ai.template.customerService.desc',
    defaults: {
      name: 'Customer Service Agent',
      description:
        'Handles customer inquiries, checks order status, and drafts professional responses',
      agent_type: 'reactive',
      personality:
        'Empathetic, patient, and solution-focused. You listen carefully to customer issues and provide clear, actionable responses while maintaining a warm and professional tone.',
      communication_style: 'friendly',
      system_prompt:
        'You are a customer service specialist. When handling inquiries:\n1. Acknowledge the customer\'s concern with empathy\n2. Check order status or account information as needed\n3. Provide a clear solution or escalation path\n4. Always end with a follow-up offer (e.g., "Is there anything else I can help you with?")\nMaintain a professional yet friendly tone throughout.',
    },
  },
  {
    id: 'data_analyst',
    icon: '📈',
    nameKey: 'ai.template.dataAnalyst.name',
    descriptionKey: 'ai.template.dataAnalyst.desc',
    defaults: {
      name: 'Data Analyst',
      description: 'Queries data, generates business insights, and creates chart recommendations',
      agent_type: 'reactive',
      personality:
        'Analytical, data-driven, and precise. You transform raw data into actionable insights, always backing conclusions with specific numbers and trends.',
      communication_style: 'detailed',
      system_prompt:
        'You are a business data analyst. When analyzing data:\n1. Identify trends, anomalies, and key patterns\n2. Calculate relevant metrics (growth rate, conversion, etc.)\n3. Provide 3-5 actionable insights based on the data\n4. Recommend appropriate chart types for visualization\nAlways present numbers with context (e.g., "Revenue grew 15% vs last month, driven primarily by...")',
    },
  },
  {
    id: 'onboarding_guide',
    icon: '🎓',
    nameKey: 'ai.template.onboarding.name',
    descriptionKey: 'ai.template.onboarding.desc',
    defaults: {
      name: 'Onboarding Guide',
      description:
        'Helps new employees navigate the platform, find resources, and complete onboarding tasks',
      agent_type: 'reactive',
      personality:
        'Welcoming, patient, and encouraging. You make new team members feel comfortable and guide them step by step, celebrating their progress along the way.',
      communication_style: 'friendly',
      system_prompt:
        'You are a new employee onboarding guide. Your responsibilities:\n1. Welcome new employees and explain the platform structure\n2. Guide them through key features: navigation, tasks, team communication\n3. Answer common questions about company processes and tools\n4. Provide links to relevant documentation and resources\n5. Check in on their progress and offer additional help\nUse encouraging language and break down complex processes into simple steps.',
    },
  },
];

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const INITIAL_DATA: WizardData = {
  name: '',
  description: '',
  avatarIcon: '',
  agent_type: 'reactive',
  personality: '',
  communication_style: 'professional',
  system_prompt: '',
  provider: '',
};

const AGENT_TYPES = [
  {
    value: 'reactive',
    labelKey: 'ai.wizard.type.reactive',
    fallback: 'Reactive',
    descKey: 'ai.wizard.type.reactive.desc',
    descFallback: 'Responds when asked. Best for Q&A and on-demand tasks.',
  },
  {
    value: 'autonomous',
    labelKey: 'ai.wizard.type.autonomous',
    fallback: 'Autonomous',
    descKey: 'ai.wizard.type.autonomous.desc',
    descFallback: 'Runs independently on schedules or triggers.',
  },
];

const COMM_STYLES = [
  { value: 'professional', labelKey: 'ai.wizard.style.professional', fallback: 'Professional' },
  { value: 'friendly', labelKey: 'ai.wizard.style.friendly', fallback: 'Friendly' },
  { value: 'concise', labelKey: 'ai.wizard.style.concise', fallback: 'Concise' },
  { value: 'detailed', labelKey: 'ai.wizard.style.detailed', fallback: 'Detailed' },
];

const AVATAR_ICONS = [
  { value: 'sparkles', icon: SparklesIcon },
  { value: 'cpu', icon: CpuChipIcon },
  { value: 'chat', icon: ChatBubbleLeftIcon },
  { value: 'clipboard', icon: ClipboardDocumentCheckIcon },
  { value: 'user', icon: UserCircleIcon },
];

const STEPS = [
  { key: 'identity', iconKey: UserCircleIcon },
  { key: 'personality', iconKey: ChatBubbleLeftIcon },
  { key: 'review', iconKey: ClipboardDocumentCheckIcon },
] as const;

// ---------------------------------------------------------------------------
// Template Selector (shown before Step 1)
// ---------------------------------------------------------------------------

function TemplateSelector({
  onSelect,
  onSkip,
  t,
}: {
  onSelect: (template: AgentTemplate) => void;
  onSkip: () => void;
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
}) {
  return (
    <div className="space-y-6" data-testid="wizard-template-selector">
      <div>
        <h2 className="text-xl font-semibold text-gray-900 dark:text-white">
          {t('ai.wizard.template.title', undefined, 'Create AI Colleague')}
        </h2>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          {t(
            'ai.wizard.template.subtitle',
            undefined,
            'Start from a template or build from scratch.',
          )}
        </p>
      </div>

      {/* Template grid */}
      <div>
        <p className="mb-3 text-xs font-semibold tracking-wider text-gray-400 uppercase dark:text-gray-500">
          {t('ai.wizard.template.sectionLabel', undefined, 'Start from a template')}
        </p>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3" data-testid="wizard-template-grid">
          {AGENT_TEMPLATES.map((tpl) => (
            <button
              key={tpl.id}
              type="button"
              onClick={() => onSelect(tpl)}
              className="group flex flex-col items-start gap-2 rounded-xl border-2 border-gray-200 bg-white p-4 text-left transition-all hover:border-blue-400 hover:bg-blue-50/50 dark:border-gray-700 dark:bg-gray-900 dark:hover:border-blue-500 dark:hover:bg-blue-900/10"
              data-testid={`wizard-template-${tpl.id}`}
            >
              <span className="text-2xl leading-none" role="img" aria-hidden="true">
                {tpl.icon}
              </span>
              <div>
                <p className="text-sm font-semibold text-gray-900 transition-colors group-hover:text-blue-700 dark:text-white dark:group-hover:text-blue-300">
                  {t(tpl.nameKey, undefined, tpl.defaults.name)}
                </p>
                <p className="mt-0.5 line-clamp-2 text-xs text-gray-500 dark:text-gray-400">
                  {t(tpl.descriptionKey, undefined, tpl.defaults.description)}
                </p>
              </div>
            </button>
          ))}
        </div>
      </div>

      {/* Skip / start from scratch */}
      <div className="flex items-center gap-3 pt-2">
        <div className="h-px flex-1 bg-gray-200 dark:bg-gray-700" />
        <button
          type="button"
          onClick={onSkip}
          className="text-sm font-medium whitespace-nowrap text-gray-500 transition-colors hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200"
          data-testid="wizard-template-skip"
        >
          {t('ai.wizard.template.startFromScratch', undefined, 'Or start from scratch →')}
        </button>
        <div className="h-px flex-1 bg-gray-200 dark:bg-gray-700" />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step Indicator
// ---------------------------------------------------------------------------

function StepIndicator({
  currentStep,
  t,
}: {
  currentStep: number;
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
}) {
  const stepLabels = [
    t('ai.wizard.step.identity', undefined, 'Identity'),
    t('ai.wizard.step.personality', undefined, 'Personality'),
    t('ai.wizard.step.review', undefined, 'Review'),
  ];

  return (
    <nav className="mb-8 flex items-center justify-center gap-2" data-testid="wizard-steps">
      {STEPS.map((step, idx) => {
        const Icon = step.iconKey;
        const isActive = idx === currentStep;
        const isDone = idx < currentStep;

        return (
          <div key={step.key} className="flex items-center">
            {idx > 0 && (
              <div
                className={`mx-1 h-0.5 w-12 transition-colors duration-300 ${
                  isDone ? 'bg-blue-500' : 'bg-gray-200 dark:bg-gray-700'
                }`}
              />
            )}
            <div className="flex items-center gap-2">
              <div
                className={`flex h-9 w-9 items-center justify-center rounded-full transition-all duration-300 ${
                  isActive
                    ? 'bg-blue-600 text-white shadow-md shadow-blue-200 dark:shadow-blue-900/40'
                    : isDone
                      ? 'bg-blue-100 text-blue-600 dark:bg-blue-900/40 dark:text-blue-400'
                      : 'bg-gray-100 text-gray-400 dark:bg-gray-800 dark:text-gray-500'
                }`}
              >
                {isDone ? <CheckIcon className="h-4 w-4" /> : <Icon className="h-4 w-4" />}
              </div>
              <span
                className={`hidden text-sm font-medium transition-colors duration-300 sm:inline ${
                  isActive
                    ? 'text-gray-900 dark:text-white'
                    : isDone
                      ? 'text-blue-600 dark:text-blue-400'
                      : 'text-gray-400 dark:text-gray-500'
                }`}
              >
                {stepLabels[idx]}
              </span>
            </div>
          </div>
        );
      })}
    </nav>
  );
}

// ---------------------------------------------------------------------------
// Step 1: Identity
// ---------------------------------------------------------------------------

function StepIdentity({
  data,
  onChange,
  errors,
  t,
}: {
  data: WizardData;
  onChange: (patch: Partial<WizardData>) => void;
  errors: Record<string, string>;
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
}) {
  return (
    <div className="space-y-6" data-testid="wizard-step-identity">
      <div>
        <h2 className="text-xl font-semibold text-gray-900 dark:text-white">
          {t('ai.wizard.identity.title', undefined, 'Define your AI Colleague')}
        </h2>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          {t(
            'ai.wizard.identity.subtitle',
            undefined,
            'Give your colleague a name, purpose, and personality.',
          )}
        </p>
      </div>

      {/* Name */}
      <div>
        <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('ai.wizard.field.name', undefined, 'Name')} <span className="text-red-500">*</span>
        </label>
        <input
          type="text"
          value={data.name}
          onChange={(e) => onChange({ name: e.target.value })}
          placeholder={t(
            'ai.wizard.field.name.placeholder',
            undefined,
            'e.g. Procurement Assistant',
          )}
          className={`w-full rounded-lg border bg-white px-3 py-2.5 text-sm dark:bg-gray-900 ${
            errors.name
              ? 'border-red-300 focus:border-red-500 focus:ring-red-500 dark:border-red-700'
              : 'border-gray-300 focus:border-blue-500 focus:ring-blue-500 dark:border-gray-600'
          } placeholder-gray-400 transition-colors focus:ring-2 focus:outline-none dark:text-white dark:placeholder-gray-500`}
          data-testid="wizard-input-name"
        />
        {errors.name && (
          <p
            className="mt-1 text-sm text-red-600 dark:text-red-400"
            data-testid="wizard-error-name"
          >
            {errors.name}
          </p>
        )}
      </div>

      {/* Description */}
      <div>
        <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('ai.wizard.field.description', undefined, 'Description')}
        </label>
        <textarea
          value={data.description}
          onChange={(e) => onChange({ description: e.target.value })}
          placeholder={t(
            'ai.wizard.field.description.placeholder',
            undefined,
            'What does this colleague do?',
          )}
          rows={3}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2.5 text-sm placeholder-gray-400 transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-900 dark:text-white dark:placeholder-gray-500"
          data-testid="wizard-input-description"
        />
      </div>

      {/* Avatar Icon */}
      <div>
        <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('ai.wizard.field.avatar', undefined, 'Avatar')}
        </label>
        <div className="flex items-center gap-3" data-testid="wizard-avatar-picker">
          {AVATAR_ICONS.map(({ value, icon: Icon }) => (
            <button
              key={value}
              type="button"
              onClick={() => onChange({ avatarIcon: data.avatarIcon === value ? '' : value })}
              className={`flex h-11 w-11 items-center justify-center rounded-full border-2 transition-all ${
                data.avatarIcon === value
                  ? 'border-blue-500 bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400'
                  : 'border-gray-200 text-gray-400 hover:border-gray-300 dark:border-gray-700 dark:text-gray-500 dark:hover:border-gray-600'
              }`}
              data-testid={`wizard-avatar-${value}`}
            >
              <Icon className="h-5 w-5" />
            </button>
          ))}
        </div>
      </div>

      {/* Agent Type */}
      <div>
        <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('ai.wizard.field.agentType', undefined, 'Agent Type')}
        </label>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2" data-testid="wizard-agent-type">
          {AGENT_TYPES.map((at) => (
            <button
              key={at.value}
              type="button"
              onClick={() => onChange({ agent_type: at.value })}
              className={`rounded-lg border-2 p-4 text-left transition-all ${
                data.agent_type === at.value
                  ? 'border-blue-500 bg-blue-50 dark:border-blue-600 dark:bg-blue-900/20'
                  : 'border-gray-200 hover:border-gray-300 dark:border-gray-700 dark:hover:border-gray-600'
              }`}
              data-testid={`wizard-type-${at.value}`}
            >
              <span
                className={`text-sm font-semibold ${
                  data.agent_type === at.value
                    ? 'text-blue-700 dark:text-blue-300'
                    : 'text-gray-900 dark:text-white'
                }`}
              >
                {t(at.labelKey, undefined, at.fallback)}
              </span>
              <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                {t(at.descKey, undefined, at.descFallback)}
              </p>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 2: Personality
// ---------------------------------------------------------------------------

function StepPersonality({
  data,
  onChange,
  t,
}: {
  data: WizardData;
  onChange: (patch: Partial<WizardData>) => void;
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
}) {
  const [showAdvanced, setShowAdvanced] = useState(!!data.system_prompt);

  return (
    <div className="space-y-6" data-testid="wizard-step-personality">
      <div>
        <h2 className="text-xl font-semibold text-gray-900 dark:text-white">
          {t('ai.wizard.personality.title', undefined, 'Shape the personality')}
        </h2>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          {t(
            'ai.wizard.personality.subtitle',
            undefined,
            'Define how your colleague communicates and behaves.',
          )}
        </p>
      </div>

      {/* Role Description */}
      <div>
        <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('ai.wizard.field.personality', undefined, 'Role Description')}
        </label>
        <textarea
          value={data.personality}
          onChange={(e) => onChange({ personality: e.target.value })}
          placeholder={t(
            'ai.wizard.field.personality.placeholder',
            undefined,
            'You are a procurement specialist who helps find the best suppliers and negotiate contracts...',
          )}
          rows={4}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2.5 text-sm placeholder-gray-400 transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-900 dark:text-white dark:placeholder-gray-500"
          data-testid="wizard-input-personality"
        />
      </div>

      {/* Communication Style */}
      <div>
        <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('ai.wizard.field.commStyle', undefined, 'Communication Style')}
        </label>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4" data-testid="wizard-comm-style">
          {COMM_STYLES.map((cs) => (
            <button
              key={cs.value}
              type="button"
              onClick={() => onChange({ communication_style: cs.value })}
              className={`rounded-lg border-2 px-3 py-2 text-sm font-medium transition-all ${
                data.communication_style === cs.value
                  ? 'border-blue-500 bg-blue-50 text-blue-700 dark:border-blue-600 dark:bg-blue-900/20 dark:text-blue-300'
                  : 'border-gray-200 text-gray-600 hover:border-gray-300 dark:border-gray-700 dark:text-gray-400 dark:hover:border-gray-600'
              }`}
              data-testid={`wizard-style-${cs.value}`}
            >
              {t(cs.labelKey, undefined, cs.fallback)}
            </button>
          ))}
        </div>
      </div>

      {/* Advanced: System Prompt */}
      <div>
        <button
          type="button"
          onClick={() => setShowAdvanced(!showAdvanced)}
          className="flex items-center gap-1.5 text-sm font-medium text-gray-600 transition-colors hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-200"
          data-testid="wizard-toggle-advanced"
        >
          {showAdvanced ? (
            <ChevronUpIcon className="h-4 w-4" />
          ) : (
            <ChevronDownIcon className="h-4 w-4" />
          )}
          {t('ai.wizard.field.systemPrompt.toggle', undefined, 'Advanced: System Prompt')}
        </button>
        {showAdvanced && (
          <div className="mt-3">
            <textarea
              value={data.system_prompt}
              onChange={(e) => onChange({ system_prompt: e.target.value })}
              placeholder={t(
                'ai.wizard.field.systemPrompt.placeholder',
                undefined,
                'Override the default system prompt. Leave empty to auto-generate from role description and communication style.',
              )}
              rows={6}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2.5 font-mono text-sm placeholder-gray-400 transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-900 dark:text-white dark:placeholder-gray-500"
              data-testid="wizard-input-system-prompt"
            />
            <p className="mt-1 text-xs text-gray-400 dark:text-gray-500">
              {t(
                'ai.wizard.field.systemPrompt.hint',
                undefined,
                'If left empty, a prompt will be generated from the role description and communication style.',
              )}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 3: Review & Create
// ---------------------------------------------------------------------------

function StepReview({
  data,
  t,
  providers,
  onChange,
}: {
  data: WizardData;
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
  providers: ConfiguredProvider[];
  onChange: (patch: Partial<WizardData>) => void;
}) {
  const agentTypeLabel = AGENT_TYPES.find((at) => at.value === data.agent_type);
  const commStyleLabel = COMM_STYLES.find((cs) => cs.value === data.communication_style);

  return (
    <div className="space-y-6" data-testid="wizard-step-review">
      <div>
        <h2 className="text-xl font-semibold text-gray-900 dark:text-white">
          {t('ai.wizard.review.title', undefined, 'Review your AI Colleague')}
        </h2>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          {t('ai.wizard.review.subtitle', undefined, 'Confirm the details before creating.')}
        </p>
      </div>

      <div className="divide-y divide-gray-200 rounded-xl border border-gray-200 bg-gray-50 dark:divide-gray-700 dark:border-gray-700 dark:bg-gray-800/50">
        {/* Identity section */}
        <div className="p-5">
          <h3 className="mb-3 text-xs font-semibold tracking-wider text-gray-400 uppercase dark:text-gray-500">
            {t('ai.wizard.step.identity', undefined, 'Identity')}
          </h3>
          <div className="space-y-3">
            <ReviewRow
              label={t('ai.wizard.field.name', undefined, 'Name')}
              value={data.name}
              testId="review-name"
            />
            <ReviewRow
              label={t('ai.wizard.field.description', undefined, 'Description')}
              value={data.description || '—'}
              testId="review-description"
            />
            <ReviewRow
              label={t('ai.wizard.field.agentType', undefined, 'Agent Type')}
              value={
                agentTypeLabel
                  ? t(agentTypeLabel.labelKey, undefined, agentTypeLabel.fallback)
                  : data.agent_type
              }
              testId="review-agent-type"
            />
            <div className="flex flex-col gap-1 sm:flex-row sm:items-center">
              <span className="w-40 shrink-0 text-sm text-gray-500 dark:text-gray-400">
                {t('ai.wizard.field.provider', undefined, 'AI service')}
              </span>
              {providers.length > 0 ? (
                <select
                  value={data.provider}
                  onChange={(e) => onChange({ provider: e.target.value })}
                  className="rounded-lg border border-gray-300 bg-white px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-900 dark:text-white"
                  data-testid="review-provider-select"
                >
                  {providers.map((p) => (
                    <option key={p.providerCode} value={p.providerCode}>
                      {p.displayName}
                    </option>
                  ))}
                </select>
              ) : (
                <span className="text-sm text-amber-600 dark:text-amber-500" data-testid="review-provider-none">
                  {t(
                    'ai.wizard.field.provider.none',
                    undefined,
                    'No AI service is configured yet — add an API key under AI Settings first, or this colleague will not be able to answer.',
                  )}
                </span>
              )}
            </div>
          </div>
        </div>

        {/* Personality section */}
        <div className="p-5">
          <h3 className="mb-3 text-xs font-semibold tracking-wider text-gray-400 uppercase dark:text-gray-500">
            {t('ai.wizard.step.personality', undefined, 'Personality')}
          </h3>
          <div className="space-y-3">
            <ReviewRow
              label={t('ai.wizard.field.personality', undefined, 'Role Description')}
              value={data.personality || '—'}
              testId="review-personality"
            />
            <ReviewRow
              label={t('ai.wizard.field.commStyle', undefined, 'Communication Style')}
              value={
                commStyleLabel
                  ? t(commStyleLabel.labelKey, undefined, commStyleLabel.fallback)
                  : data.communication_style
              }
              testId="review-comm-style"
            />
            {data.system_prompt && (
              <ReviewRow
                label={t('ai.wizard.field.systemPrompt.toggle', undefined, 'System Prompt')}
                value={data.system_prompt}
                mono
                testId="review-system-prompt"
              />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function ReviewRow({
  label,
  value,
  mono,
  testId,
}: {
  label: string;
  value: string;
  mono?: boolean;
  testId?: string;
}) {
  return (
    <div className="flex flex-col gap-1 sm:flex-row sm:items-start" data-testid={testId}>
      <span className="shrink-0 text-sm font-medium text-gray-500 sm:w-40 dark:text-gray-400">
        {label}
      </span>
      <span
        className={`text-sm break-words text-gray-900 dark:text-white ${mono ? 'rounded border border-gray-200 bg-white p-2 font-mono text-xs whitespace-pre-wrap dark:border-gray-700 dark:bg-gray-900' : ''}`}
      >
        {value}
      </span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Wizard
// ---------------------------------------------------------------------------

export function AgentCreateWizard(_props?: { block?: unknown; runtime?: unknown }) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const toast = useToastContext();

  // showTemplatePicker: true = show template selector; false = show 3-step wizard
  const [showTemplatePicker, setShowTemplatePicker] = useState(true);
  const [step, setStep] = useState(0);
  const [data, setData] = useState<WizardData>(INITIAL_DATA);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [creating, setCreating] = useState(false);
  const [providers, setProviders] = useState<ConfiguredProvider[]>([]);

  // Which LLM services this tenant has a key for. Without this the colleague is created against
  // the column default, which names a vendor the tenant may never have configured — the record
  // saves, appears in the list, enrols into the org chart, and then cannot answer a single
  // message. Offering only configured providers means a colleague that exists can talk.
  useEffect(() => {
    let cancelled = false;
    // Plain fetch, not the shared `get()`. This endpoint answers with a bare array rather than the
    // {code, data} envelope the rest of the API uses — a shape an existing e2e spec pins down — and
    // the shared client normalises every response into that envelope. Spreading an array through
    // that normaliser turns it into an object with numeric keys and a null `data`, so the list
    // arrives empty and the wizard reports "no AI service configured" for a tenant that has two.
    fetch('/api/agent/providers/configured', { headers: { Accept: 'application/json' } })
      .then((r) => (r.ok ? r.json() : []))
      .then((body: unknown) => {
        if (cancelled) return;
        const list = Array.isArray(body) ? (body as ConfiguredProvider[]) : [];
        if (list.length === 0) return;
        setProviders(list);
        setData((prev) => (prev.provider ? prev : { ...prev, provider: list[0].providerCode }));
      })
      .catch(() => {
        /* Leave the selector empty; create() refuses rather than binding to a dead default. */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const onChange = useCallback(
    (patch: Partial<WizardData>) => {
      setData((prev) => ({ ...prev, ...patch }));
      // Clear errors for changed fields
      const clearedErrors = { ...errors };
      for (const key of Object.keys(patch)) {
        delete clearedErrors[key];
      }
      setErrors(clearedErrors);
    },
    [errors],
  );

  /** Apply a template preset and jump directly into Step 1 (identity) */
  // Both handlers keep the resolved provider. It is not part of the template — it comes from what
  // the tenant has configured, and the fetch that resolves it has usually already landed by the
  // time anyone clicks. Resetting to INITIAL_DATA wholesale blanked it, and the colleague was
  // created with an empty provider: model cleared, nothing to use in its place, and the failure
  // only visible when someone tried to talk to it.
  const handleSelectTemplate = (tpl: AgentTemplate) => {
    setData((prev) => ({
      ...INITIAL_DATA,
      ...tpl.defaults,
      provider: prev.provider,
    }));
    setErrors({});
    setStep(0);
    setShowTemplatePicker(false);
  };

  /** Skip template selection and start fresh */
  const handleSkipTemplate = () => {
    setData((prev) => ({ ...INITIAL_DATA, provider: prev.provider }));
    setErrors({});
    setStep(0);
    setShowTemplatePicker(false);
  };

  const validateStep = (s: number): boolean => {
    const newErrors: Record<string, string> = {};
    if (s === 0) {
      if (!data.name.trim()) {
        newErrors.name = t('ai.wizard.error.nameRequired', undefined, 'Name is required');
      }
    }
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleNext = () => {
    if (!validateStep(step)) return;
    setStep((s) => Math.min(s + 1, 2));
  };

  const handleBack = () => {
    if (step === 0) {
      // Go back to template picker
      setShowTemplatePicker(true);
    } else {
      setStep((s) => Math.max(s - 1, 0));
    }
  };

  const handleCreate = async () => {
    if (!validateStep(0)) {
      setStep(0);
      return;
    }
    // Refuse rather than create a colleague that cannot answer. Without a provider the record
    // still saves and still enrols — the only place the emptiness shows up is the first message,
    // long after whoever created it has moved on.
    if (!data.provider) {
      toast.showErrorToast(
        t(
          'ai.wizard.error.noProvider',
          undefined,
          'Choose an AI service first — a colleague without one cannot answer. Add an API key under AI Settings if the list is empty.',
        ),
      );
      return;
    }
    setCreating(true);
    try {
      const payload: Record<string, any> = {
        name: data.name.trim(),
        agent_code: deriveAgentCode(data.name.trim(), Date.now().toString(36)),
        agent_type: data.agent_type,
        communication_style: data.communication_style,
        status: 'active',
        // Bind the colleague to a service the tenant has configured, and clear the model so the
        // provider's own default is used. Both halves are needed: `model` carries a column default
        // naming one vendor's model, and leaving it set would send that model name to whichever
        // provider was chosen here — an agent that resolves a provider and then asks it for a
        // model it has never heard of.
        guardrails: JSON.stringify({ provider: data.provider }),
        model: null,
      };
      if (data.description.trim()) payload.description = data.description.trim();
      if (data.personality.trim()) payload.personality = data.personality.trim();
      if (data.system_prompt.trim()) payload.system_prompt = data.system_prompt.trim();

      const res = await post<{ pid: string }>('/api/dynamic/agent-definition/create', payload);
      if (ResultHelper.isSuccess(res) && res.data?.pid) {
        toast.showSuccessToast(
          t('ai.wizard.success', undefined, 'AI Colleague created successfully'),
        );
        navigate(`/ai/colleagues/${res.data.pid}`);
      } else {
        toast.showErrorToast(
          t('ai.wizard.error.createFailed', undefined, 'Failed to create AI Colleague'),
        );
      }
    } catch {
      toast.showErrorToast(
        t('ai.wizard.error.createFailed', undefined, 'Failed to create AI Colleague'),
      );
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="flex min-h-[calc(100vh-4rem)] flex-col items-center p-6">
      <div className="w-full max-w-2xl">
        {/* Back to list */}
        <button
          onClick={() => navigate('/p/c/ai_colleagues')}
          className="mb-6 inline-flex items-center gap-1.5 text-sm text-gray-500 transition-colors hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200"
          data-testid="wizard-back-to-list"
        >
          <ArrowLeftIcon className="h-4 w-4" />
          {t('ai.wizard.backToList', undefined, 'Back to AI Colleagues')}
        </button>

        {/* Template Picker (step 0 of the overall flow) */}
        {showTemplatePicker ? (
          <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm sm:p-8 dark:border-gray-700 dark:bg-gray-900">
            <TemplateSelector onSelect={handleSelectTemplate} onSkip={handleSkipTemplate} t={t} />
          </div>
        ) : (
          <>
            {/* Step Indicator */}
            <StepIndicator currentStep={step} t={t} />

            {/* Step Content */}
            <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm sm:p-8 dark:border-gray-700 dark:bg-gray-900">
              {step === 0 && <StepIdentity data={data} onChange={onChange} errors={errors} t={t} />}
              {step === 1 && <StepPersonality data={data} onChange={onChange} t={t} />}
              {step === 2 && (
                <StepReview data={data} t={t} providers={providers} onChange={onChange} />
              )}

              {/* Navigation Buttons */}
              <div className="mt-8 flex items-center justify-between border-t border-gray-200 pt-6 dark:border-gray-700">
                <div>
                  <button
                    onClick={handleBack}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-gray-200 px-4 py-2.5 text-sm font-medium text-gray-600 transition-colors hover:bg-gray-100 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-gray-800"
                    data-testid="wizard-btn-back"
                  >
                    <ArrowLeftIcon className="h-4 w-4" />
                    {t('ai.wizard.btn.back', undefined, 'Back')}
                  </button>
                </div>
                <div>
                  {step < 2 ? (
                    <button
                      onClick={handleNext}
                      className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-medium text-white shadow-sm transition-colors hover:bg-blue-700"
                      data-testid="wizard-btn-next"
                    >
                      {t('ai.wizard.btn.next', undefined, 'Next')}
                      <ArrowRightIcon className="h-4 w-4" />
                    </button>
                  ) : (
                    <button
                      onClick={handleCreate}
                      disabled={creating}
                      className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-medium text-white shadow-sm transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
                      data-testid="wizard-btn-create"
                    >
                      {creating ? (
                        <>
                          <svg className="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
                            <circle
                              className="opacity-25"
                              cx="12"
                              cy="12"
                              r="10"
                              stroke="currentColor"
                              strokeWidth="4"
                            />
                            <path
                              className="opacity-75"
                              fill="currentColor"
                              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                            />
                          </svg>
                          {t('ai.wizard.btn.creating', undefined, 'Creating...')}
                        </>
                      ) : (
                        <>
                          <CheckIcon className="h-4 w-4" />
                          {t('ai.wizard.btn.create', undefined, 'Create AI Colleague')}
                        </>
                      )}
                    </button>
                  )}
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

export default AgentCreateWizard;
