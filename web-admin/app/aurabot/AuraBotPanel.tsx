import { useState, useEffect, useCallback, useRef } from 'react';
import { useAuraBot } from './AuraBotProvider';
import { AuraBotChat } from './components/AuraBotChat';
import { ContextSuggestions } from './components/ContextSuggestions';
import { ActionBar } from './components/ActionBar';
import { XMarkIcon, Cog6ToothIcon, ChevronUpDownIcon } from '@heroicons/react/24/outline';
import { useNavigate } from 'react-router';

interface AgentOption {
  agent_code: string;
  agent_name: string;
  agent_status?: string;
}

const AURABOT_DEFAULT: AgentOption = {
  agent_code: 'aurabot',
  agent_name: 'AuraBot',
};

export function AuraBotPanel() {
  const { state, closePanel, sendMessage, setSelectedAgent } = useAuraBot();
  const navigate = useNavigate();
  const [agents, setAgents] = useState<AgentOption[]>([AURABOT_DEFAULT]);
  const [selectorOpen, setSelectorOpen] = useState(false);
  const selectorRef = useRef<HTMLDivElement>(null);

  // Fetch available ACP agents when panel expands
  useEffect(() => {
    if (state.panelState !== 'expanded') return;

    const loadAgents = async () => {
      try {
        const res = await fetch('/api/dynamic/agent-definition/list?pageSize=50&sortField=agent_code&sortOrder=ASC', {
          credentials: 'include',
        });
        if (!res.ok) return;
        const json = await res.json();
        const records: AgentOption[] = json?.data?.records || [];
        const activeAgents = records.filter(
          (a) => a.agent_status === 'active' || a.agent_status === 'published',
        );
        setAgents([AURABOT_DEFAULT, ...activeAgents]);
      } catch {
        // Silently ignore — keep default AuraBot only
      }
    };
    loadAgents();
  }, [state.panelState]);

  // Close dropdown when clicking outside
  useEffect(() => {
    if (!selectorOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (selectorRef.current && !selectorRef.current.contains(e.target as Node)) {
        setSelectorOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [selectorOpen]);

  const handleAgentSelect = useCallback(
    (agentCode: string) => {
      setSelectedAgent(agentCode);
      setSelectorOpen(false);
    },
    [setSelectedAgent],
  );

  if (state.panelState === 'collapsed') return null;

  const handleSuggestionClick = (prompt: string) => {
    sendMessage(prompt);
  };

  const handleSendPrompt = (prompt: string) => {
    sendMessage(prompt);
  };

  const currentAgent = agents.find((a) => a.agent_code === state.selectedAgentCode) || AURABOT_DEFAULT;

  return (
    <div
      className="aurabot-panel print-hide flex h-full w-[380px] min-w-[380px] flex-col border-l border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800"
      data-testid="aurabot-panel"
      data-print="hide"
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-gradient-to-r from-blue-50 to-indigo-50 px-4 py-3 dark:border-gray-700 dark:from-blue-900/20 dark:to-indigo-900/20">
        <div ref={selectorRef} className="relative flex items-center gap-2">
          <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-blue-500 to-indigo-500">
            <span className="text-sm text-white">&#10022;</span>
          </div>
          {/* Agent selector trigger */}
          <button
            onClick={() => setSelectorOpen(!selectorOpen)}
            className="flex items-center gap-1 rounded-md px-1.5 py-0.5 text-sm font-semibold text-gray-800 transition-colors hover:bg-white/60 dark:text-gray-200 dark:hover:bg-gray-700/50"
            data-testid="agent-selector-trigger"
          >
            {currentAgent.agent_name || currentAgent.agent_code}
            {agents.length > 1 && <ChevronUpDownIcon className="h-3.5 w-3.5 text-gray-400" />}
          </button>

          {/* Agent selector dropdown */}
          {selectorOpen && agents.length > 1 && (
            <div
              className="absolute top-full left-0 z-50 mt-1 min-w-[200px] rounded-lg border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-600 dark:bg-gray-800"
              data-testid="agent-selector-dropdown"
            >
              {agents.map((agent) => (
                <button
                  key={agent.agent_code}
                  onClick={() => handleAgentSelect(agent.agent_code)}
                  className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors hover:bg-gray-50 dark:hover:bg-gray-700 ${
                    agent.agent_code === state.selectedAgentCode
                      ? 'bg-blue-50 text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
                      : 'text-gray-700 dark:text-gray-300'
                  }`}
                >
                  <span className="flex h-5 w-5 items-center justify-center rounded text-xs">
                    {agent.agent_code === 'aurabot' ? '\u2726' : '\u{1F9D1}\u200D\u{1F4BC}'}
                  </span>
                  <span className="truncate">{agent.agent_name || agent.agent_code}</span>
                  {agent.agent_code === state.selectedAgentCode && (
                    <svg className="ml-auto h-4 w-4 flex-shrink-0 text-blue-500" viewBox="0 0 20 20" fill="currentColor">
                      <path fillRule="evenodd" d="M16.704 4.153a.75.75 0 01.143 1.052l-8 10.5a.75.75 0 01-1.127.075l-4.5-4.5a.75.75 0 011.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 011.05-.143z" clipRule="evenodd" />
                    </svg>
                  )}
                </button>
              ))}
            </div>
          )}
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={() => navigate('/aurabot/providers')}
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-white/50 hover:text-gray-600 dark:hover:bg-gray-700/50 dark:hover:text-gray-300"
            title="LLM Settings"
          >
            <Cog6ToothIcon className="h-4 w-4" />
          </button>
          <button
            onClick={closePanel}
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-white/50 hover:text-gray-600 dark:hover:bg-gray-700/50 dark:hover:text-gray-300"
            title="Close (⌘J)"
          >
            <XMarkIcon className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Context badge */}
      {state.pageContext?.modelCode && (
        <div className="truncate border-b border-gray-100 bg-gray-50 px-4 py-1.5 text-xs text-gray-500 dark:border-gray-700 dark:bg-gray-900/30 dark:text-gray-400">
          {state.pageContext.breadcrumb?.join(' \u2192 ') || state.pageContext.modelCode}
          {state.pageContext.pageType === 'detail' && state.pageContext.recordPid && (
            <span className="ml-1 text-blue-500">&bull; Record</span>
          )}
        </div>
      )}

      {/* Suggestions */}
      <ContextSuggestions
        pageContext={state.pageContext}
        onSuggestionClick={handleSuggestionClick}
      />

      {/* Chat */}
      <div className="flex-1 overflow-hidden">
        <AuraBotChat />
      </div>

      {/* Actions */}
      <ActionBar pageContext={state.pageContext} onSendPrompt={handleSendPrompt} />
    </div>
  );
}

export default AuraBotPanel;
