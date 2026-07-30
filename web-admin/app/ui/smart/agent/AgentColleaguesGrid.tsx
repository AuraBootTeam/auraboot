/**
 * AgentColleaguesGrid — custom DSL block for the AI Colleagues card grid.
 *
 * Ported from the hand-written pages/ai/colleagues.tsx so the page can be a DSL page
 * (ai_colleagues, kind:detail) that renders `{ blockType: "custom", component:
 * "AgentColleaguesGrid" }`. The rich, agent-specific presentation the generic card-grid
 * cannot express — AuraBot pinned first as a read-only "official" card, per-card conditional
 * actions (chat everywhere, edit only for non-AuraBot), computed visibility badges — lives
 * here as a registered platform component (the §7-sanctioned custom-block escape), not a
 * bespoke file route.
 *
 * Self-contained: fetches agent-definition rows and navigates to the (still-React) detail /
 * chat / new routes; those convert in later slices.
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
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { workspacePageClassName } from '~/shared/layout/WorkspacePageLayout';

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

const AURABOT_CODE = 'aurabot';

const TYPE_COLORS: Record<string, string> = {
  reactive: 'bg-accent-weak text-accent dark:bg-accent-weak/30 dark:text-accent',
  copilot: 'bg-accent-weak text-accent dark:bg-accent-weak/30 dark:text-accent',
  autonomous:
    'bg-status-amber-bg text-status-amber dark:bg-status-amber-bg/30 dark:text-status-amber',
  workflow:
    'bg-status-green-bg text-status-green dark:bg-status-green-bg/30 dark:text-status-green',
};

const STATUS_STYLES: Record<string, { dot: string; text: string }> = {
  active: { dot: 'bg-status-green-bg0', text: 'text-status-green dark:text-status-green' },
  disabled: { dot: 'bg-status-gray-bg', text: 'text-text-3 dark:text-text-3' },
  draft: { dot: 'bg-status-amber-bg0', text: 'text-status-amber dark:text-status-amber' },
};

function getInitial(name: string): string {
  if (!name) return '?';
  const first = name.charAt(0);
  if (/[一-鿿㐀-䶿]/.test(first)) return first;
  return first.toUpperCase();
}

function avatarColor(str: string): string {
  const colors = [
    'bg-accent',
    'bg-accent-weak0',
    'bg-accent-weak0',
    'bg-pink-500',
    'bg-status-red-bg0',
    'bg-status-amber-bg0',
    'bg-status-amber-bg0',
    'bg-status-green-bg0',
    'bg-accent-weak0',
    'bg-status-green-bg0',
  ];
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
  }
  return colors[Math.abs(hash) % colors.length];
}

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
      className={`${sizeMap[size]} flex items-center justify-center rounded-full font-semibold text-white ${isAuraBot ? 'from-accent-weak0 to-accent-hover bg-gradient-to-br' : avatarColor(agent.agent_code)}`}
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
  const cls = TYPE_COLORS[agentType] ?? 'bg-subtle text-text-2 dark:bg-subtle dark:text-text-3';
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ${cls}`}
    >
      <CpuChipIcon className="h-3 w-3" />
      {agentType}
    </span>
  );
}

function VisibilityBadge({ visibility }: { visibility: 'private' | 'team' | 'tenant' | null }) {
  const v = visibility ?? 'private';
  if (v === 'private') {
    return (
      <span
        className="bg-subtle text-text-3 dark:bg-subtle dark:text-text-3 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium"
        data-testid="visibility-badge-private"
      >
        <LockClosedIcon className="h-3 w-3" />
        Private
      </span>
    );
  }
  if (v === 'team') {
    return (
      <span
        className="bg-accent-weak text-accent dark:bg-accent-weak/30 dark:text-accent inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium"
        data-testid="visibility-badge-team"
      >
        <UserGroupIcon className="h-3 w-3" />
        Team
      </span>
    );
  }
  return (
    <span
      className="bg-accent-weak text-accent dark:bg-accent-weak/30 dark:text-accent inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium"
      data-testid="visibility-badge-tenant"
    >
      <GlobeAltIcon className="h-3 w-3" />
      Shared
    </span>
  );
}

function AuraBotCard({ agent, onChat }: { agent: AgentRecord; onChat: () => void }) {
  const { t } = useI18n();
  return (
    <div
      className="border-accent from-accent-weak to-accent-weak dark:border-accent dark:from-accent/40 dark:via-subtle dark:to-accent-hover/30 relative overflow-hidden rounded-xl border-2 bg-gradient-to-br via-white p-5 shadow-sm transition-all duration-200 hover:shadow-md"
      data-testid="aurabot-card"
    >
      <div className="absolute top-3 right-3 flex items-center gap-1.5">
        <span className="bg-accent inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold text-white">
          {t('ai.colleagues.badge.official', undefined, 'Official')}
        </span>
        <span className="from-accent-weak0 to-accent-weak0 inline-flex items-center gap-1 rounded-full bg-gradient-to-r px-2 py-0.5 text-[11px] font-semibold text-white">
          <BoltIcon className="h-3 w-3" />
          {t('ai.colleagues.badge.fullPower', undefined, 'Full Power')}
        </span>
      </div>

      <div className="mt-1 flex items-start gap-4">
        <AgentAvatar agent={agent} size="lg" />
        <div className="min-w-0 flex-1">
          <h3 className="text-text truncate text-lg font-semibold dark:text-white">{agent.name}</h3>
          <p className="text-text-3 dark:text-text-3 mt-1 line-clamp-2 text-sm">
            {agent.description ||
              t(
                'ai.colleagues.aurabot.desc',
                undefined,
                'Built-in AI assistant with full data access',
              )}
          </p>
        </div>
      </div>

      <div className="mt-4 flex items-center justify-between">
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={agent.status} />
          <TypeBadge agentType={agent.agent_type} />
          <VisibilityBadge visibility="tenant" />
        </div>
        <button
          onClick={onChat}
          className="bg-accent hover:bg-accent-hover inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors"
          data-testid="aurabot-chat-btn"
        >
          <ChatBubbleLeftRightIcon className="h-4 w-4" />
          {t('ai.colleagues.action.chat', undefined, 'Chat')}
        </button>
      </div>
    </div>
  );
}

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
      className="border-border bg-panel hover:border-border-strong dark:border-border dark:bg-subtle dark:hover:border-border-strong rounded-xl border p-5 shadow-sm transition-all duration-200 hover:shadow-md"
      data-testid={`agent-card-${agent.agent_code}`}
    >
      <div className="flex items-start gap-3">
        <AgentAvatar agent={agent} />
        <div className="min-w-0 flex-1">
          <h3 className="text-text truncate text-base font-semibold dark:text-white">
            {agent.name}
          </h3>
          <p className="text-text-3 dark:text-text-3 mt-0.5 line-clamp-2 text-sm">
            {agent.description || t('ai.colleagues.noDescription', undefined, 'No description')}
          </p>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <StatusBadge status={agent.status} />
        <TypeBadge agentType={agent.agent_type} />
        <VisibilityBadge visibility={agent.visibility} />
        {agent.model && (
          <span className="text-text-3 dark:text-text-3 truncate text-[11px]">{agent.model}</span>
        )}
      </div>

      <div className="mt-4 flex items-center justify-end gap-2">
        <button
          onClick={onEdit}
          className="border-border text-text-2 hover:bg-subtle dark:border-border dark:text-text-3 dark:hover:bg-subtle inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors"
          data-testid={`agent-edit-${agent.agent_code}`}
        >
          <PencilSquareIcon className="h-4 w-4" />
          {t('ai.colleagues.action.edit', undefined, 'Edit')}
        </button>
        <button
          onClick={onChat}
          className="bg-accent hover:bg-accent-hover inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-sm font-medium text-white transition-colors"
          data-testid={`agent-chat-${agent.agent_code}`}
        >
          <ChatBubbleLeftRightIcon className="h-4 w-4" />
          {t('ai.colleagues.action.chat', undefined, 'Chat')}
        </button>
      </div>
    </div>
  );
}

function EmptyState({ onCreate }: { onCreate: () => void }) {
  const { t } = useI18n();
  return (
    <div className="col-span-full flex flex-col items-center justify-center py-20 text-center">
      <UserCircleIcon className="text-text-3 dark:text-text-2 mb-4 h-16 w-16" />
      <h3 className="text-text text-lg font-medium dark:text-white">
        {t('ai.colleagues.empty.title', undefined, 'No AI colleagues yet')}
      </h3>
      <p className="text-text-3 dark:text-text-3 mt-1 max-w-sm text-sm">
        {t(
          'ai.colleagues.empty.description',
          undefined,
          'Create your first AI colleague to automate tasks and enhance your workflow.',
        )}
      </p>
      <button
        onClick={onCreate}
        className="bg-accent hover:bg-accent-hover mt-4 inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors"
      >
        <PlusIcon className="h-4 w-4" />
        {t('ai.colleagues.create', undefined, 'Create AI Colleague')}
      </button>
    </div>
  );
}

function CardSkeleton() {
  return (
    <div className="border-border bg-panel dark:border-border dark:bg-subtle animate-pulse rounded-xl border p-5">
      <div className="flex items-start gap-3">
        <div className="bg-border dark:bg-subtle h-12 w-12 rounded-full" />
        <div className="flex-1 space-y-2">
          <div className="bg-border dark:bg-subtle h-4 w-1/2 rounded" />
          <div className="bg-border dark:bg-subtle h-3 w-3/4 rounded" />
        </div>
      </div>
      <div className="mt-3 flex gap-2">
        <div className="bg-border dark:bg-subtle h-5 w-14 rounded-full" />
        <div className="bg-border dark:bg-subtle h-5 w-16 rounded-full" />
      </div>
      <div className="mt-4 flex justify-end gap-2">
        <div className="bg-border dark:bg-subtle h-8 w-16 rounded-lg" />
        <div className="bg-border dark:bg-subtle h-8 w-16 rounded-lg" />
      </div>
    </div>
  );
}

/**
 * The custom block itself. Receives the standard block/runtime props from the DSL renderer
 * but is self-contained (fetches its own data and navigates directly), so they are unused.
 */
export function AgentColleaguesGrid(_props?: { block?: unknown; runtime?: unknown }) {
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
      toast.showErrorToast(
        t('ai.colleagues.error.loadFailed', undefined, 'Failed to load AI colleagues'),
      );
    } finally {
      setLoading(false);
    }
  }, [toast, t]);

  useEffect(() => {
    fetchAgents();
  }, [fetchAgents]);

  const sortedAgents = useMemo(() => {
    return [...agents].sort((a, b) => {
      if (a.agent_code === AURABOT_CODE) return -1;
      if (b.agent_code === AURABOT_CODE) return 1;
      if (a.status !== b.status) return a.status === 'active' ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
  }, [agents]);

  const handleCreate = () => navigate('/p/c/ai_colleague_new');
  const handleEdit = (agent: AgentRecord) =>
    navigate(`/p/c/ai_colleague_detail?agentPid=${agent.pid}`);
  const handleChat = (agent: AgentRecord) =>
    navigate(`/p/c/ai_colleague_chat?agentPid=${agent.pid}`);

  return (
    <div className={workspacePageClassName('contentPadded')} data-testid="agent-colleagues-grid">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-text text-2xl font-semibold dark:text-white">
            {t('ai.colleagues.title', undefined, 'AI Colleagues')}
          </h1>
          <p className="text-text-3 dark:text-text-3 mt-1 text-sm">
            {t('ai.colleagues.subtitle', undefined, 'Manage your AI team members')}
          </p>
        </div>
        <button
          onClick={handleCreate}
          className="bg-accent hover:bg-accent-hover inline-flex items-center gap-1.5 rounded-lg px-4 py-2.5 text-sm font-medium text-white shadow-sm transition-colors"
          data-testid="create-agent-btn"
        >
          <PlusIcon className="h-4 w-4" />
          {t('ai.colleagues.create', undefined, 'Create AI Colleague')}
        </button>
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
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
              <AuraBotCard key={agent.pid} agent={agent} onChat={() => handleChat(agent)} />
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

export default AgentColleaguesGrid;
