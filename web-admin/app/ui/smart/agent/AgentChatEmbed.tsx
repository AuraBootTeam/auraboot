/**
 * AgentChatEmbed — custom DSL block for the full-page AI colleague chat.
 *
 * Ported from pages/ai/colleagues.$agentPid.chat.tsx so the page can be a DSL page
 * (ai_colleague_chat, kind:detail) rendering { blockType:"custom", component:"AgentChatEmbed" }.
 * A streaming conversational UI cannot be pure DSL config, so it stays a registered platform
 * component wrapping the existing AuraBotChat — the §7-sanctioned custom-block escape. The
 * agent to talk to comes from the ?agentPid= query parameter (the DSL /p/c/ route has no path
 * param), replacing the old :agentPid route segment.
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useSearchParams, Link } from 'react-router';
import {
  ArrowLeftIcon,
  Cog6ToothIcon,
  SparklesIcon,
  ArrowPathIcon,
} from '@heroicons/react/24/outline';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useAuraBot, AuraBotChat } from '~/plugins/core-aurabot/components-shell';
import { useI18n } from '~/contexts/I18nContext';

interface AgentInfo {
  pid: string;
  agent_code: string;
  name: string;
  description: string | null;
  avatar_url: string | null;
  agent_type: string;
  model: string | null;
  status: string;
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

function AgentAvatar({ agent }: { agent: AgentInfo }) {
  const isAuraBot = agent.agent_code === 'aurabot';

  if (agent.avatar_url) {
    return (
      <img src={agent.avatar_url} alt={agent.name} className="h-9 w-9 rounded-full object-cover" />
    );
  }

  const initial = agent.name?.charAt(0)?.toUpperCase() || '?';

  return (
    <div
      className={`flex h-9 w-9 items-center justify-center rounded-full font-semibold text-white ${isAuraBot ? 'from-accent-weak0 to-accent-hover bg-gradient-to-br' : avatarColor(agent.agent_code)}`}
    >
      {isAuraBot ? <SparklesIcon className="h-5 w-5" /> : initial}
    </div>
  );
}

export function AgentChatEmbed(_props?: { block?: unknown; runtime?: unknown }) {
  const [searchParams] = useSearchParams();
  const agentPid = searchParams.get('agentPid') || undefined;
  const navigate = useNavigate();
  const { t } = useI18n();
  const { setSelectedAgent, newSession } = useAuraBot();

  const [agent, setAgent] = useState<AgentInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadAgent = useCallback(async () => {
    if (!agentPid) {
      setError(t('ai.chat.error.notFound', undefined, 'Agent not found'));
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const res = await get<AgentInfo>(`/api/dynamic/agent-definition/${agentPid}`);
      if (ResultHelper.isSuccess(res) && res.data) {
        setAgent(res.data);
      } else {
        setError(t('ai.chat.error.notFound', undefined, 'Agent not found'));
      }
    } catch {
      setError(t('ai.chat.error.loadFailed', undefined, 'Failed to load agent'));
    } finally {
      setLoading(false);
    }
  }, [agentPid, t]);

  useEffect(() => {
    loadAgent();
  }, [loadAgent]);

  useEffect(() => {
    if (agent?.agent_code) {
      setSelectedAgent(agent.agent_code);
    }
  }, [agent?.agent_code, setSelectedAgent]);

  const handleNewSession = useCallback(() => {
    newSession();
  }, [newSession]);

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="border-accent0 h-8 w-8 animate-spin rounded-full border-2 border-t-transparent" />
      </div>
    );
  }

  if (error || !agent) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4">
        <p className="text-text-3 text-sm">{error || 'Agent not found'}</p>
        <Link to="/p/c/ai_colleagues" className="text-accent hover:text-accent text-sm font-medium">
          {t('ai.chat.backToColleagues', undefined, 'Back to AI Colleagues')}
        </Link>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col" data-testid="agent-chat-page">
      <div className="border-border bg-panel dark:border-border dark:bg-subtle flex items-center justify-between border-b px-4 py-3">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/p/c/ai_colleagues')}
            className="text-text-3 hover:bg-subtle hover:text-text-2 dark:hover:bg-subtle dark:hover:text-text-3 rounded-lg p-1.5 transition-colors"
            title={t('ai.chat.back', undefined, 'Back')}
            data-testid="agent-chat-back-btn"
          >
            <ArrowLeftIcon className="h-5 w-5" />
          </button>

          <AgentAvatar agent={agent} />

          <div className="min-w-0">
            <h1 className="text-text truncate text-sm font-semibold dark:text-white">
              {agent.name}
            </h1>
            {agent.description && (
              <p className="text-text-3 dark:text-text-3 truncate text-xs">{agent.description}</p>
            )}
          </div>
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={handleNewSession}
            className="text-text-3 hover:bg-subtle hover:text-text-2 dark:hover:bg-subtle dark:hover:text-text-3 rounded-lg p-1.5 transition-colors"
            title={t('ai.chat.newSession', undefined, 'New Conversation')}
            data-testid="agent-chat-new-session-btn"
          >
            <ArrowPathIcon className="h-4 w-4" />
          </button>

          <button
            onClick={() => navigate(`/p/c/ai_colleague_detail?agentPid=${agentPid}`)}
            className="text-text-3 hover:bg-subtle hover:text-text-2 dark:hover:bg-subtle dark:hover:text-text-3 rounded-lg p-1.5 transition-colors"
            title={t('ai.chat.settings', undefined, 'Agent Settings')}
            data-testid="agent-chat-settings-btn"
          >
            <Cog6ToothIcon className="h-4 w-4" />
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-hidden">
        <AuraBotChat />
      </div>
    </div>
  );
}

export default AgentChatEmbed;
