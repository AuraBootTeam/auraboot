/**
 * AI Colleagues — Card Grid Page
 *
 * Displays all AI agents as cards. AuraBot appears first as a read-only
 * "official" agent. Other agents can be edited / chatted with.
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router';
import {
  PlusIcon,
  ChatBubbleLeftRightIcon,
  PencilSquareIcon,
  SparklesIcon,
  CpuChipIcon,
  UserCircleIcon,
  BoltIcon,
  LockClosedIcon,
  UserGroupIcon,
  GlobeAltIcon,
} from '@heroicons/react/24/outline';
import { get } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface AgentRecord {
  pid: string;
  agent_code: string;
  name: string;
  description: string | null;
  avatar_url: string | null;
  agent_type: string;
  model: string | null;
  status: string;
  visibility: 'private' | 'team' | 'tenant' | null;
  personality: string | null;
  expertise: string | null;
  communication_style: string | null;
  created_at: string;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const AURABOT_CODE = 'aurabot';

const TYPE_COLORS: Record<string, string> = {
  reactive: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
  copilot: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300',
  autonomous: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
  workflow: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
};

const STATUS_STYLES: Record<string, { dot: string; text: string }> = {
  active: { dot: 'bg-green-500', text: 'text-green-700 dark:text-green-400' },
  disabled: { dot: 'bg-gray-400', text: 'text-gray-500 dark:text-gray-400' },
  draft: { dot: 'bg-yellow-500', text: 'text-yellow-700 dark:text-yellow-400' },
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Extract initials (first letter) from agent name */
function getInitial(name: string): string {
  if (!name) return '?';
  // For CJK characters, just return the first character
  const first = name.charAt(0);
  if (/[\u4e00-\u9fff\u3400-\u4dbf]/.test(first)) return first;
  return first.toUpperCase();
}

/** Deterministic color for avatar background based on string hash */
function avatarColor(str: string): string {
  const colors = [
    'bg-blue-500', 'bg-indigo-500', 'bg-purple-500', 'bg-pink-500',
    'bg-rose-500', 'bg-orange-500', 'bg-amber-500', 'bg-teal-500',
    'bg-cyan-500', 'bg-emerald-500',
  ];
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
  }
  return colors[Math.abs(hash) % colors.length];
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function AgentAvatar({ agent, size = 'md' }: { agent: AgentRecord; size?: 'sm' | 'md' | 'lg' }) {
  const sizeMap = { sm: 'h-8 w-8 text-sm', md: 'h-12 w-12 text-lg', lg: 'h-16 w-16 text-2xl' };
  const isAuraBot = agent.agent_code === AURABOT_CODE;

  if (agent.avatar_url) {
    return (
      <img
        src={agent.avatar_url}
        alt={agent.name}
        className={`${sizeMap[size]} rounded-full object-cover`}
      />
    );
  }

  return (
    <div
      className={`${sizeMap[size]} rounded-full flex items-center justify-center font-semibold text-white
        ${isAuraBot ? 'bg-gradient-to-br from-blue-500 to-violet-600' : avatarColor(agent.agent_code)}`}
    >
      {isAuraBot ? <SparklesIcon className="h-6 w-6" /> : getInitial(agent.name)}
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const style = STATUS_STYLES[status] ?? STATUS_STYLES.draft;
  const { t } = useI18n();
  const label = t(`ai.colleagues.status.${status}`, undefined, status);
  return (
    <span className={`inline-flex items-center gap-1 text-xs font-medium ${style.text}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${style.dot}`} />
      {label}
    </span>
  );
}

function TypeBadge({ agentType }: { agentType: string }) {
  const cls = TYPE_COLORS[agentType] ?? 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300';
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium ${cls}`}>
      <CpuChipIcon className="h-3 w-3" />
      {agentType}
    </span>
  );
}

function VisibilityBadge({ visibility }: { visibility: 'private' | 'team' | 'tenant' | null }) {
  const v = visibility ?? 'private';
  if (v === 'private') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium
        bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-400"
        data-testid="visibility-badge-private"
      >
        <LockClosedIcon className="h-3 w-3" />
        Private
      </span>
    );
  }
  if (v === 'team') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium
        bg-purple-100 text-purple-600 dark:bg-purple-900/30 dark:text-purple-300"
        data-testid="visibility-badge-team"
      >
        <UserGroupIcon className="h-3 w-3" />
        Team
      </span>
    );
  }
  // tenant
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium
      bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-300"
      data-testid="visibility-badge-tenant"
    >
      <GlobeAltIcon className="h-3 w-3" />
      Shared
    </span>
  );
}

// ---------------------------------------------------------------------------
// AuraBot Card (special)
// ---------------------------------------------------------------------------

function AuraBotCard({ agent, onChat }: { agent: AgentRecord; onChat: () => void }) {
  const { t } = useI18n();
  return (
    <div
      className="relative overflow-hidden rounded-xl border-2 border-blue-200 dark:border-blue-800
        bg-gradient-to-br from-blue-50 via-white to-violet-50
        dark:from-blue-950/40 dark:via-gray-900 dark:to-violet-950/30
        shadow-sm hover:shadow-md transition-all duration-200 p-5"
      data-testid="aurabot-card"
    >
      {/* Official badge ribbon */}
      <div className="absolute top-3 right-3 flex items-center gap-1.5">
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold
          bg-blue-600 text-white">
          {t('ai.colleagues.badge.official', undefined, 'Official')}
        </span>
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold
          bg-gradient-to-r from-violet-500 to-blue-500 text-white">
          <BoltIcon className="h-3 w-3" />
          {t('ai.colleagues.badge.fullPower', undefined, 'Full Power')}
        </span>
      </div>

      <div className="flex items-start gap-4 mt-1">
        <AgentAvatar agent={agent} size="lg" />
        <div className="flex-1 min-w-0">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white truncate">
            {agent.name}
          </h3>
          <p className="text-sm text-gray-500 dark:text-gray-400 mt-1 line-clamp-2">
            {agent.description || t('ai.colleagues.aurabot.desc', undefined, 'Built-in AI assistant with full data access')}
          </p>
        </div>
      </div>

      <div className="mt-4 flex items-center justify-between">
        <div className="flex items-center gap-2 flex-wrap">
          <StatusBadge status={agent.status} />
          <TypeBadge agentType={agent.agent_type} />
          <VisibilityBadge visibility="tenant" />
        </div>
        <button
          onClick={onChat}
          className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium
            bg-blue-600 text-white hover:bg-blue-700 transition-colors"
          data-testid="aurabot-chat-btn"
        >
          <ChatBubbleLeftRightIcon className="h-4 w-4" />
          {t('ai.colleagues.action.chat', undefined, 'Chat')}
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Regular Agent Card
// ---------------------------------------------------------------------------

function AgentCard({
  agent,
  onEdit,
  onChat,
}: {
  agent: AgentRecord;
  onEdit: () => void;
  onChat: () => void;
}) {
  const { t } = useI18n();
  return (
    <div
      className="rounded-xl border border-gray-200 dark:border-gray-700
        bg-white dark:bg-gray-900
        shadow-sm hover:shadow-md hover:border-gray-300 dark:hover:border-gray-600
        transition-all duration-200 p-5"
      data-testid={`agent-card-${agent.agent_code}`}
    >
      <div className="flex items-start gap-3">
        <AgentAvatar agent={agent} />
        <div className="flex-1 min-w-0">
          <h3 className="text-base font-semibold text-gray-900 dark:text-white truncate">
            {agent.name}
          </h3>
          <p className="text-sm text-gray-500 dark:text-gray-400 mt-0.5 line-clamp-2">
            {agent.description || t('ai.colleagues.noDescription', undefined, 'No description')}
          </p>
        </div>
      </div>

      <div className="mt-3 flex items-center gap-2 flex-wrap">
        <StatusBadge status={agent.status} />
        <TypeBadge agentType={agent.agent_type} />
        <VisibilityBadge visibility={agent.visibility} />
        {agent.model && (
          <span className="text-[11px] text-gray-400 dark:text-gray-500 truncate">
            {agent.model}
          </span>
        )}
      </div>

      <div className="mt-4 flex items-center justify-end gap-2">
        <button
          onClick={onEdit}
          className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-sm font-medium
            text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800
            border border-gray-200 dark:border-gray-700 transition-colors"
          data-testid={`agent-edit-${agent.agent_code}`}
        >
          <PencilSquareIcon className="h-4 w-4" />
          {t('ai.colleagues.action.edit', undefined, 'Edit')}
        </button>
        <button
          onClick={onChat}
          className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-sm font-medium
            bg-blue-600 text-white hover:bg-blue-700 transition-colors"
          data-testid={`agent-chat-${agent.agent_code}`}
        >
          <ChatBubbleLeftRightIcon className="h-4 w-4" />
          {t('ai.colleagues.action.chat', undefined, 'Chat')}
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Empty State
// ---------------------------------------------------------------------------

function EmptyState({ onCreate }: { onCreate: () => void }) {
  const { t } = useI18n();
  return (
    <div className="col-span-full flex flex-col items-center justify-center py-20 text-center">
      <UserCircleIcon className="h-16 w-16 text-gray-300 dark:text-gray-600 mb-4" />
      <h3 className="text-lg font-medium text-gray-900 dark:text-white">
        {t('ai.colleagues.empty.title', undefined, 'No AI colleagues yet')}
      </h3>
      <p className="text-sm text-gray-500 dark:text-gray-400 mt-1 max-w-sm">
        {t('ai.colleagues.empty.description', undefined, 'Create your first AI colleague to automate tasks and enhance your workflow.')}
      </p>
      <button
        onClick={onCreate}
        className="mt-4 inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium
          bg-blue-600 text-white hover:bg-blue-700 transition-colors"
      >
        <PlusIcon className="h-4 w-4" />
        {t('ai.colleagues.create', undefined, 'Create AI Colleague')}
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Loading Skeleton
// ---------------------------------------------------------------------------

function CardSkeleton() {
  return (
    <div className="rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 p-5 animate-pulse">
      <div className="flex items-start gap-3">
        <div className="h-12 w-12 rounded-full bg-gray-200 dark:bg-gray-700" />
        <div className="flex-1 space-y-2">
          <div className="h-4 w-1/2 rounded bg-gray-200 dark:bg-gray-700" />
          <div className="h-3 w-3/4 rounded bg-gray-200 dark:bg-gray-700" />
        </div>
      </div>
      <div className="mt-3 flex gap-2">
        <div className="h-5 w-14 rounded-full bg-gray-200 dark:bg-gray-700" />
        <div className="h-5 w-16 rounded-full bg-gray-200 dark:bg-gray-700" />
      </div>
      <div className="mt-4 flex justify-end gap-2">
        <div className="h-8 w-16 rounded-lg bg-gray-200 dark:bg-gray-700" />
        <div className="h-8 w-16 rounded-lg bg-gray-200 dark:bg-gray-700" />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Page
// ---------------------------------------------------------------------------

export default function AIColleaguesPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const toast = useToastContext();
  const [agents, setAgents] = useState<AgentRecord[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchAgents = useCallback(async () => {
    try {
      const res = await get<{ records: AgentRecord[]; total: number }>(
        '/api/dynamic/agent-definition/list',
        { pageNum: 1, pageSize: 500 },
      );
      if (ResultHelper.isSuccess(res) && res.data?.records) {
        setAgents(res.data.records);
      }
    } catch {
      toast.showErrorToast(t('ai.colleagues.error.loadFailed', undefined, 'Failed to load AI colleagues'));
    } finally {
      setLoading(false);
    }
  }, [toast, t]);

  useEffect(() => {
    fetchAgents();
  }, [fetchAgents]);

  // Sort: AuraBot first, then active before disabled, then by name
  const sortedAgents = useMemo(() => {
    return [...agents].sort((a, b) => {
      if (a.agent_code === AURABOT_CODE) return -1;
      if (b.agent_code === AURABOT_CODE) return 1;
      if (a.status !== b.status) return a.status === 'active' ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
  }, [agents]);

  const handleCreate = () => {
    navigate('/ai/colleagues/new');
  };

  const handleEdit = (agent: AgentRecord) => {
    navigate(`/ai/colleagues/${agent.pid}`);
  };

  const handleChat = (agent: AgentRecord) => {
    // Navigate to full-page chat for all agents (including AuraBot)
    navigate(`/ai/colleagues/${agent.pid}/chat`);
  };

  return (
    <div className="p-6 max-w-7xl mx-auto">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900 dark:text-white">
            {t('ai.colleagues.title', undefined, 'AI Colleagues')}
          </h1>
          <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
            {t('ai.colleagues.subtitle', undefined, 'Manage your AI team members')}
          </p>
        </div>
        <button
          onClick={handleCreate}
          className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-lg text-sm font-medium
            bg-blue-600 text-white hover:bg-blue-700 shadow-sm transition-colors"
          data-testid="create-agent-btn"
        >
          <PlusIcon className="h-4 w-4" />
          {t('ai.colleagues.create', undefined, 'Create AI Colleague')}
        </button>
      </div>

      {/* Card Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {loading ? (
          <>
            <CardSkeleton />
            <CardSkeleton />
            <CardSkeleton />
            <CardSkeleton />
            <CardSkeleton />
            <CardSkeleton />
          </>
        ) : sortedAgents.length === 0 ? (
          <EmptyState onCreate={handleCreate} />
        ) : (
          sortedAgents.map((agent) =>
            agent.agent_code === AURABOT_CODE ? (
              <AuraBotCard
                key={agent.pid}
                agent={agent}
                onChat={() => handleChat(agent)}
              />
            ) : (
              <AgentCard
                key={agent.pid}
                agent={agent}
                onEdit={() => handleEdit(agent)}
                onChat={() => handleChat(agent)}
              />
            ),
          )
        )}
      </div>
    </div>
  );
}
