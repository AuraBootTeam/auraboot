/**
 * AI Colleague Creation Wizard — template selection + 3-step guided flow
 *
 * Step 0 (template picker): Choose a pre-built template or start from scratch
 * Step 1: Identity (name, description, avatar, agent type)
 * Step 2: Personality (role description, communication style, system prompt)
 * Step 3: Review & Create (summary + submit)
 */

import { useState, useCallback } from 'react';
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
import { post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';

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
}

interface AgentTemplate {
  id: string;
  icon: string;
  nameKey: string;
  descriptionKey: string;
  defaults: Omit<WizardData, 'avatarIcon'>;
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
      personality: 'Meticulous, detail-oriented, follows rules strictly. You verify data before making decisions and always provide clear reasoning for approvals or rejections.',
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
      personality: 'Organized, concise, and thorough. You synthesize information from multiple sources into clear, structured reports that highlight progress, blockers, and next steps.',
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
      description: 'Handles customer inquiries, checks order status, and drafts professional responses',
      agent_type: 'reactive',
      personality: 'Empathetic, patient, and solution-focused. You listen carefully to customer issues and provide clear, actionable responses while maintaining a warm and professional tone.',
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
      personality: 'Analytical, data-driven, and precise. You transform raw data into actionable insights, always backing conclusions with specific numbers and trends.',
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
      description: 'Helps new employees navigate the platform, find resources, and complete onboarding tasks',
      agent_type: 'reactive',
      personality: 'Welcoming, patient, and encouraging. You make new team members feel comfortable and guide them step by step, celebrating their progress along the way.',
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
};

const AGENT_TYPES = [
  { value: 'reactive', labelKey: 'ai.wizard.type.reactive', fallback: 'Reactive', descKey: 'ai.wizard.type.reactive.desc', descFallback: 'Responds when asked. Best for Q&A and on-demand tasks.' },
  { value: 'autonomous', labelKey: 'ai.wizard.type.autonomous', fallback: 'Autonomous', descKey: 'ai.wizard.type.autonomous.desc', descFallback: 'Runs independently on schedules or triggers.' },
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
        <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
          {t('ai.wizard.template.subtitle', undefined, 'Start from a template or build from scratch.')}
        </p>
      </div>

      {/* Template grid */}
      <div>
        <p className="text-xs font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500 mb-3">
          {t('ai.wizard.template.sectionLabel', undefined, 'Start from a template')}
        </p>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3" data-testid="wizard-template-grid">
          {AGENT_TEMPLATES.map((tpl) => (
            <button
              key={tpl.id}
              type="button"
              onClick={() => onSelect(tpl)}
              className="flex flex-col items-start gap-2 p-4 rounded-xl border-2 border-gray-200 dark:border-gray-700
                bg-white dark:bg-gray-900
                hover:border-blue-400 dark:hover:border-blue-500
                hover:bg-blue-50/50 dark:hover:bg-blue-900/10
                transition-all text-left group"
              data-testid={`wizard-template-${tpl.id}`}
            >
              <span className="text-2xl leading-none" role="img" aria-hidden="true">
                {tpl.icon}
              </span>
              <div>
                <p className="text-sm font-semibold text-gray-900 dark:text-white group-hover:text-blue-700 dark:group-hover:text-blue-300 transition-colors">
                  {t(tpl.nameKey, undefined, tpl.defaults.name)}
                </p>
                <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5 line-clamp-2">
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
          className="text-sm text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200
            font-medium transition-colors whitespace-nowrap"
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
    <nav className="flex items-center justify-center gap-2 mb-8" data-testid="wizard-steps">
      {STEPS.map((step, idx) => {
        const Icon = step.iconKey;
        const isActive = idx === currentStep;
        const isDone = idx < currentStep;

        return (
          <div key={step.key} className="flex items-center">
            {idx > 0 && (
              <div
                className={`w-12 h-0.5 mx-1 transition-colors duration-300 ${
                  isDone ? 'bg-blue-500' : 'bg-gray-200 dark:bg-gray-700'
                }`}
              />
            )}
            <div className="flex items-center gap-2">
              <div
                className={`flex items-center justify-center h-9 w-9 rounded-full transition-all duration-300 ${
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
                className={`text-sm font-medium hidden sm:inline transition-colors duration-300 ${
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
        <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
          {t('ai.wizard.identity.subtitle', undefined, 'Give your colleague a name, purpose, and personality.')}
        </p>
      </div>

      {/* Name */}
      <div>
        <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1.5">
          {t('ai.wizard.field.name', undefined, 'Name')} <span className="text-red-500">*</span>
        </label>
        <input
          type="text"
          value={data.name}
          onChange={(e) => onChange({ name: e.target.value })}
          placeholder={t('ai.wizard.field.name.placeholder', undefined, 'e.g. Procurement Assistant')}
          className={`w-full rounded-lg border px-3 py-2.5 text-sm
            bg-white dark:bg-gray-900
            ${errors.name
              ? 'border-red-300 dark:border-red-700 focus:ring-red-500 focus:border-red-500'
              : 'border-gray-300 dark:border-gray-600 focus:ring-blue-500 focus:border-blue-500'
            }
            dark:text-white placeholder-gray-400 dark:placeholder-gray-500
            focus:outline-none focus:ring-2 transition-colors`}
          data-testid="wizard-input-name"
        />
        {errors.name && (
          <p className="mt-1 text-sm text-red-600 dark:text-red-400" data-testid="wizard-error-name">
            {errors.name}
          </p>
        )}
      </div>

      {/* Description */}
      <div>
        <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1.5">
          {t('ai.wizard.field.description', undefined, 'Description')}
        </label>
        <textarea
          value={data.description}
          onChange={(e) => onChange({ description: e.target.value })}
          placeholder={t('ai.wizard.field.description.placeholder', undefined, 'What does this colleague do?')}
          rows={3}
          className="w-full rounded-lg border border-gray-300 dark:border-gray-600 px-3 py-2.5 text-sm
            bg-white dark:bg-gray-900 dark:text-white
            placeholder-gray-400 dark:placeholder-gray-500
            focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors"
          data-testid="wizard-input-description"
        />
      </div>

      {/* Avatar Icon */}
      <div>
        <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1.5">
          {t('ai.wizard.field.avatar', undefined, 'Avatar')}
        </label>
        <div className="flex items-center gap-3" data-testid="wizard-avatar-picker">
          {AVATAR_ICONS.map(({ value, icon: Icon }) => (
            <button
              key={value}
              type="button"
              onClick={() => onChange({ avatarIcon: data.avatarIcon === value ? '' : value })}
              className={`flex items-center justify-center h-11 w-11 rounded-full border-2 transition-all ${
                data.avatarIcon === value
                  ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400'
                  : 'border-gray-200 dark:border-gray-700 text-gray-400 dark:text-gray-500 hover:border-gray-300 dark:hover:border-gray-600'
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
        <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1.5">
          {t('ai.wizard.field.agentType', undefined, 'Agent Type')}
        </label>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3" data-testid="wizard-agent-type">
          {AGENT_TYPES.map((at) => (
            <button
              key={at.value}
              type="button"
              onClick={() => onChange({ agent_type: at.value })}
              className={`text-left rounded-lg border-2 p-4 transition-all ${
                data.agent_type === at.value
                  ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/20 dark:border-blue-600'
                  : 'border-gray-200 dark:border-gray-700 hover:border-gray-300 dark:hover:border-gray-600'
              }`}
              data-testid={`wizard-type-${at.value}`}
            >
              <span className={`text-sm font-semibold ${
                data.agent_type === at.value
                  ? 'text-blue-700 dark:text-blue-300'
                  : 'text-gray-900 dark:text-white'
              }`}>
                {t(at.labelKey, undefined, at.fallback)}
              </span>
              <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
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
        <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
          {t('ai.wizard.personality.subtitle', undefined, 'Define how your colleague communicates and behaves.')}
        </p>
      </div>

      {/* Role Description */}
      <div>
        <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1.5">
          {t('ai.wizard.field.personality', undefined, 'Role Description')}
        </label>
        <textarea
          value={data.personality}
          onChange={(e) => onChange({ personality: e.target.value })}
          placeholder={t('ai.wizard.field.personality.placeholder', undefined, 'You are a procurement specialist who helps find the best suppliers and negotiate contracts...')}
          rows={4}
          className="w-full rounded-lg border border-gray-300 dark:border-gray-600 px-3 py-2.5 text-sm
            bg-white dark:bg-gray-900 dark:text-white
            placeholder-gray-400 dark:placeholder-gray-500
            focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors"
          data-testid="wizard-input-personality"
        />
      </div>

      {/* Communication Style */}
      <div>
        <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1.5">
          {t('ai.wizard.field.commStyle', undefined, 'Communication Style')}
        </label>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2" data-testid="wizard-comm-style">
          {COMM_STYLES.map((cs) => (
            <button
              key={cs.value}
              type="button"
              onClick={() => onChange({ communication_style: cs.value })}
              className={`rounded-lg border-2 px-3 py-2 text-sm font-medium transition-all ${
                data.communication_style === cs.value
                  ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/20 text-blue-700 dark:text-blue-300 dark:border-blue-600'
                  : 'border-gray-200 dark:border-gray-700 text-gray-600 dark:text-gray-400 hover:border-gray-300 dark:hover:border-gray-600'
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
          className="flex items-center gap-1.5 text-sm font-medium text-gray-600 dark:text-gray-400
            hover:text-gray-900 dark:hover:text-gray-200 transition-colors"
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
              className="w-full rounded-lg border border-gray-300 dark:border-gray-600 px-3 py-2.5 text-sm font-mono
                bg-white dark:bg-gray-900 dark:text-white
                placeholder-gray-400 dark:placeholder-gray-500
                focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors"
              data-testid="wizard-input-system-prompt"
            />
            <p className="mt-1 text-xs text-gray-400 dark:text-gray-500">
              {t('ai.wizard.field.systemPrompt.hint', undefined, 'If left empty, a prompt will be generated from the role description and communication style.')}
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
}: {
  data: WizardData;
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
}) {
  const agentTypeLabel = AGENT_TYPES.find((at) => at.value === data.agent_type);
  const commStyleLabel = COMM_STYLES.find((cs) => cs.value === data.communication_style);

  return (
    <div className="space-y-6" data-testid="wizard-step-review">
      <div>
        <h2 className="text-xl font-semibold text-gray-900 dark:text-white">
          {t('ai.wizard.review.title', undefined, 'Review your AI Colleague')}
        </h2>
        <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
          {t('ai.wizard.review.subtitle', undefined, 'Confirm the details before creating.')}
        </p>
      </div>

      <div className="rounded-xl border border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/50 divide-y divide-gray-200 dark:divide-gray-700">
        {/* Identity section */}
        <div className="p-5">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500 mb-3">
            {t('ai.wizard.step.identity', undefined, 'Identity')}
          </h3>
          <div className="space-y-3">
            <ReviewRow label={t('ai.wizard.field.name', undefined, 'Name')} value={data.name} testId="review-name" />
            <ReviewRow
              label={t('ai.wizard.field.description', undefined, 'Description')}
              value={data.description || '—'}
              testId="review-description"
            />
            <ReviewRow
              label={t('ai.wizard.field.agentType', undefined, 'Agent Type')}
              value={agentTypeLabel ? t(agentTypeLabel.labelKey, undefined, agentTypeLabel.fallback) : data.agent_type}
              testId="review-agent-type"
            />
          </div>
        </div>

        {/* Personality section */}
        <div className="p-5">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500 mb-3">
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
              value={commStyleLabel ? t(commStyleLabel.labelKey, undefined, commStyleLabel.fallback) : data.communication_style}
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
    <div className="flex flex-col sm:flex-row sm:items-start gap-1" data-testid={testId}>
      <span className="text-sm font-medium text-gray-500 dark:text-gray-400 sm:w-40 shrink-0">{label}</span>
      <span
        className={`text-sm text-gray-900 dark:text-white break-words ${mono ? 'font-mono text-xs whitespace-pre-wrap bg-white dark:bg-gray-900 rounded p-2 border border-gray-200 dark:border-gray-700' : ''}`}
      >
        {value}
      </span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Wizard
// ---------------------------------------------------------------------------

export default function AIColleagueNewPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const toast = useToastContext();

  // showTemplatePicker: true = show template selector; false = show 3-step wizard
  const [showTemplatePicker, setShowTemplatePicker] = useState(true);
  const [step, setStep] = useState(0);
  const [data, setData] = useState<WizardData>(INITIAL_DATA);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [creating, setCreating] = useState(false);

  const onChange = useCallback((patch: Partial<WizardData>) => {
    setData((prev) => ({ ...prev, ...patch }));
    // Clear errors for changed fields
    const clearedErrors = { ...errors };
    for (const key of Object.keys(patch)) {
      delete clearedErrors[key];
    }
    setErrors(clearedErrors);
  }, [errors]);

  /** Apply a template preset and jump directly into Step 1 (identity) */
  const handleSelectTemplate = (tpl: AgentTemplate) => {
    setData({
      ...INITIAL_DATA,
      ...tpl.defaults,
    });
    setErrors({});
    setStep(0);
    setShowTemplatePicker(false);
  };

  /** Skip template selection and start fresh */
  const handleSkipTemplate = () => {
    setData(INITIAL_DATA);
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
    setCreating(true);
    try {
      const payload: Record<string, any> = {
        name: data.name.trim(),
        agent_type: data.agent_type,
        communication_style: data.communication_style,
        status: 'active',
      };
      if (data.description.trim()) payload.description = data.description.trim();
      if (data.personality.trim()) payload.personality = data.personality.trim();
      if (data.system_prompt.trim()) payload.system_prompt = data.system_prompt.trim();

      const res = await post<{ pid: string }>('/api/dynamic/agent-definition/create', payload);
      if (ResultHelper.isSuccess(res) && res.data?.pid) {
        toast.showSuccessToast(t('ai.wizard.success', undefined, 'AI Colleague created successfully'));
        navigate(`/ai/colleagues/${res.data.pid}`);
      } else {
        toast.showErrorToast(t('ai.wizard.error.createFailed', undefined, 'Failed to create AI Colleague'));
      }
    } catch {
      toast.showErrorToast(t('ai.wizard.error.createFailed', undefined, 'Failed to create AI Colleague'));
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="min-h-[calc(100vh-4rem)] flex flex-col items-center p-6">
      <div className="w-full max-w-2xl">
        {/* Back to list */}
        <button
          onClick={() => navigate('/ai/colleagues')}
          className="inline-flex items-center gap-1.5 text-sm text-gray-500 dark:text-gray-400
            hover:text-gray-700 dark:hover:text-gray-200 mb-6 transition-colors"
          data-testid="wizard-back-to-list"
        >
          <ArrowLeftIcon className="h-4 w-4" />
          {t('ai.wizard.backToList', undefined, 'Back to AI Colleagues')}
        </button>

        {/* Template Picker (step 0 of the overall flow) */}
        {showTemplatePicker ? (
          <div className="bg-white dark:bg-gray-900 rounded-xl border border-gray-200 dark:border-gray-700 shadow-sm p-6 sm:p-8">
            <TemplateSelector onSelect={handleSelectTemplate} onSkip={handleSkipTemplate} t={t} />
          </div>
        ) : (
          <>
            {/* Step Indicator */}
            <StepIndicator currentStep={step} t={t} />

            {/* Step Content */}
            <div className="bg-white dark:bg-gray-900 rounded-xl border border-gray-200 dark:border-gray-700 shadow-sm p-6 sm:p-8">
              {step === 0 && <StepIdentity data={data} onChange={onChange} errors={errors} t={t} />}
              {step === 1 && <StepPersonality data={data} onChange={onChange} t={t} />}
              {step === 2 && <StepReview data={data} t={t} />}

              {/* Navigation Buttons */}
              <div className="flex items-center justify-between mt-8 pt-6 border-t border-gray-200 dark:border-gray-700">
                <div>
                  <button
                    onClick={handleBack}
                    className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-lg text-sm font-medium
                      text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800
                      border border-gray-200 dark:border-gray-700 transition-colors"
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
                      className="inline-flex items-center gap-1.5 px-5 py-2.5 rounded-lg text-sm font-medium
                        bg-blue-600 text-white hover:bg-blue-700 shadow-sm transition-colors"
                      data-testid="wizard-btn-next"
                    >
                      {t('ai.wizard.btn.next', undefined, 'Next')}
                      <ArrowRightIcon className="h-4 w-4" />
                    </button>
                  ) : (
                    <button
                      onClick={handleCreate}
                      disabled={creating}
                      className="inline-flex items-center gap-1.5 px-5 py-2.5 rounded-lg text-sm font-medium
                        bg-blue-600 text-white hover:bg-blue-700 shadow-sm transition-colors
                        disabled:opacity-50 disabled:cursor-not-allowed"
                      data-testid="wizard-btn-create"
                    >
                      {creating ? (
                        <>
                          <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24" fill="none">
                            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
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
