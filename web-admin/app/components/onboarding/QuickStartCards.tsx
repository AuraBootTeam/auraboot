/**
 * QuickStartCards — Always-visible quick-start shortcuts on the dashboard.
 */

import { Link } from 'react-router';
import {
  RocketLaunchIcon,
  PaintBrushIcon,
  BookOpenIcon,
  ChatBubbleLeftRightIcon,
} from '@heroicons/react/24/outline';
import { useAuraBot } from '~/aurabot';

const CARDS = [
  {
    title: 'Application Templates',
    description: 'Pre-built apps ready to install',
    icon: RocketLaunchIcon,
    to: '/admin/templates',
    color: 'bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400',
  },
  {
    title: 'Page Designer',
    description: 'Build custom data-driven pages',
    icon: PaintBrushIcon,
    to: '/page-designer',
    color: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-900/30 dark:text-indigo-400',
  },
  {
    title: 'Dashboards',
    description: 'Create analytics dashboards',
    icon: BookOpenIcon,
    to: '/dashboards',
    color: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-400',
  },
] as const;

export function QuickStartCards() {
  const { openPanel } = useAuraBot();

  return (
    <div className="mb-6">
      <h3 className="mb-3 text-sm font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
        Quick Start
      </h3>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {CARDS.map((card) => (
          <Link
            key={card.to}
            to={card.to}
            className="group flex items-start gap-3 rounded-xl border border-gray-200 bg-white p-4 transition-all hover:-translate-y-0.5 hover:shadow-md dark:border-gray-700 dark:bg-gray-800"
          >
            <div className={`rounded-lg p-2 ${card.color}`}>
              <card.icon className="h-5 w-5" />
            </div>
            <div className="min-w-0">
              <h4 className="text-sm font-semibold text-gray-900 transition-colors group-hover:text-blue-600 dark:text-white dark:group-hover:text-blue-400">
                {card.title}
              </h4>
              <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{card.description}</p>
            </div>
          </Link>
        ))}

        {/* AI Assistant — button, not a link */}
        <button
          onClick={openPanel}
          className="group flex items-start gap-3 rounded-xl border border-gray-200 bg-white p-4 text-left transition-all hover:-translate-y-0.5 hover:shadow-md dark:border-gray-700 dark:bg-gray-800"
        >
          <div className="rounded-lg bg-violet-50 p-2 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
            <ChatBubbleLeftRightIcon className="h-5 w-5" />
          </div>
          <div className="min-w-0">
            <h4 className="text-sm font-semibold text-gray-900 transition-colors group-hover:text-blue-600 dark:text-white dark:group-hover:text-blue-400">
              AI Assistant
            </h4>
            <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">Ask AuraBot anything</p>
          </div>
        </button>
      </div>
    </div>
  );
}
