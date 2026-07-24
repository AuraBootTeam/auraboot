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
    'bg-blue-500',
    'bg-indigo-500',
    'bg-purple-500',
    'bg-pink-500',
    'bg-rose-500',
    'bg-orange-500',
    'bg-amber-500',
    'bg-teal-500',
    'bg-cyan-500',
    'bg-emerald-500',
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
      className={`flex h-9 w-9 items-center justify-center rounded-full font-semibold text-white ${isAuraBot ? 'bg-gradient-to-br from-blue-500 to-violet-600' : avatarColor(agent.agent_code)}`}
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
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  if (error || !agent) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4">
        <p className="text-sm text-gray-500">{error || 'Agent not found'}</p>
        <Link
          to="/p/c/ai_colleagues"
          className="text-sm font-medium text-blue-600 hover:text-blue-700"
        >
          {t('ai.chat.backToColleagues', undefined, 'Back to AI Colleagues')}
        </Link>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col" data-testid="agent-chat-page">
      <div className="flex items-center justify-between border-b border-gray-200 bg-white px-4 py-3 dark:border-gray-700 dark:bg-gray-900">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/p/c/ai_colleagues')}
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-800 dark:hover:text-gray-300"
            title={t('ai.chat.back', undefined, 'Back')}
            data-testid="agent-chat-back-btn"
          >
            <ArrowLeftIcon className="h-5 w-5" />
          </button>

          <AgentAvatar agent={agent} />

          <div className="min-w-0">
            <h1 className="truncate text-sm font-semibold text-gray-900 dark:text-white">
              {agent.name}
            </h1>
            {agent.description && (
              <p className="truncate text-xs text-gray-500 dark:text-gray-400">
                {agent.description}
              </p>
            )}
          </div>
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={handleNewSession}
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-800 dark:hover:text-gray-300"
            title={t('ai.chat.newSession', undefined, 'New Conversation')}
            data-testid="agent-chat-new-session-btn"
          >
            <ArrowPathIcon className="h-4 w-4" />
          </button>

          <button
            onClick={() => navigate(`/p/c/ai_colleague_detail?agentPid=${agentPid}`)}
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-800 dark:hover:text-gray-300"
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
