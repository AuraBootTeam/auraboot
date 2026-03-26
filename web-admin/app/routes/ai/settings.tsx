/**
 * AI Center — Settings Hub Page
 *
 * A landing page with cards linking to each AI settings area.
 * Individual settings pages remain as-is; this hub provides navigation entry.
 */

import { useNavigate } from 'react-router';
import {
  KeyIcon,
  ServerIcon,
  DocumentTextIcon,
  TagIcon,
  BookOpenIcon,
  ShieldCheckIcon,
} from '@heroicons/react/24/outline';

interface SettingsItem {
  titleKey: string;
  descriptionKey: string;
  path: string;
  Icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
}

const SETTINGS_ITEMS: SettingsItem[] = [
  {
    titleKey: 'LLM Providers',
    descriptionKey: 'Configure AI model providers and API keys',
    path: '/aurabot/providers',
    Icon: KeyIcon,
  },
  {
    titleKey: 'MCP Servers',
    descriptionKey: 'Connect external tool servers via MCP protocol',
    path: '/dynamic/mcp-server',
    Icon: ServerIcon,
  },
  {
    titleKey: 'Prompt Templates',
    descriptionKey: 'Manage reusable prompt templates',
    path: '/aurabot/prompts',
    Icon: DocumentTextIcon,
  },
  {
    titleKey: 'Object Aliases',
    descriptionKey: 'Configure natural language aliases for data models',
    path: '/dynamic/object-alias',
    Icon: TagIcon,
  },
  {
    titleKey: 'Semantic Terms',
    descriptionKey: 'Define domain-specific vocabulary',
    path: '/dynamic/semantic-term',
    Icon: BookOpenIcon,
  },
  {
    titleKey: 'Governance Policies',
    descriptionKey: 'Set approval rules for agent actions',
    path: '/dynamic/approval-policy',
    Icon: ShieldCheckIcon,
  },
];

export default function AISettingsPage() {
  const navigate = useNavigate();

  return (
    <div className="p-6">
      <h1 className="text-2xl font-semibold mb-2">AI Settings</h1>
      <p className="text-sm text-gray-500 mb-6">
        Configure providers, tools, and governance for the AI center.
      </p>
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {SETTINGS_ITEMS.map(({ titleKey, descriptionKey, path, Icon }) => (
          <button
            key={path}
            type="button"
            onClick={() => navigate(path)}
            className="text-left p-5 border border-gray-200 rounded-lg cursor-pointer hover:shadow-md hover:border-blue-300 transition-all duration-150 bg-white focus:outline-none focus:ring-2 focus:ring-blue-400"
          >
            <div className="flex items-center gap-3 mb-2">
              <Icon className="h-6 w-6 text-blue-500 shrink-0" />
              <h3 className="font-medium text-gray-800">{titleKey}</h3>
            </div>
            <p className="text-sm text-gray-500">{descriptionKey}</p>
          </button>
        ))}
      </div>
    </div>
  );
}
