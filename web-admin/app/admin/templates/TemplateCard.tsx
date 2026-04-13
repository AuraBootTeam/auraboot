/**
 * TemplateCard — Card component for a single template in the Template Center.
 *
 * Shows preview image (or placeholder), title, description, feature badges,
 * model/command stats, and installed status. Clicking navigates to the
 * template preview page.
 */

import { useNavigate } from 'react-router';
import { CubeIcon, CommandLineIcon, CheckCircleIcon } from '@heroicons/react/24/outline';
import type { AppTemplate } from './templateCatalog';

const COLOR_MAP: Record<string, { badge: string; accent: string }> = {
  blue: {
    badge: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300',
    accent: 'bg-blue-500',
  },
  indigo: {
    badge: 'bg-indigo-100 text-indigo-700 dark:bg-indigo-900/40 dark:text-indigo-300',
    accent: 'bg-indigo-500',
  },
  amber: {
    badge: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300',
    accent: 'bg-amber-500',
  },
  emerald: {
    badge: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300',
    accent: 'bg-emerald-500',
  },
  violet: {
    badge: 'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300',
    accent: 'bg-violet-500',
  },
};

function getColor(color: string) {
  return COLOR_MAP[color] ?? COLOR_MAP.blue;
}

interface TemplateCardProps {
  template: AppTemplate;
  installed: boolean;
}

export function TemplateCard({ template, installed }: TemplateCardProps) {
  const navigate = useNavigate();
  const c = getColor(template.color);

  return (
    <div
      onClick={() => navigate(`/admin/templates/${template.id}/preview`)}
      className={`group relative cursor-pointer overflow-hidden rounded-xl border bg-white transition-all duration-200 hover:-translate-y-1 hover:shadow-lg dark:bg-gray-800 ${
        installed
          ? 'border-green-300 dark:border-green-700'
          : 'border-gray-200 dark:border-gray-700'
      }`}
      data-testid={`template-card-${template.id}`}
    >
      {/* Preview image area */}
      <div className="relative aspect-video bg-gray-100 dark:bg-gray-700/50">
        {template.previewImage ? (
          <img
            src={template.previewImage}
            alt={template.name}
            className="h-full w-full object-cover"
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <span className="text-5xl opacity-60" role="img">
              {template.icon}
            </span>
          </div>
        )}

        {/* Installed badge overlay */}
        {installed && (
          <div className="absolute top-2 right-2 flex items-center gap-1 rounded-full bg-green-500 px-2 py-0.5 text-xs font-medium text-white shadow">
            <CheckCircleIcon className="h-3.5 w-3.5" />
            Installed
          </div>
        )}
      </div>

      {/* Content */}
      <div className="p-4">
        {/* Title */}
        <h3 className="mb-1 font-semibold text-gray-900 dark:text-white">{template.name}</h3>

        {/* Description */}
        <p className="mb-3 line-clamp-2 text-sm text-gray-500 dark:text-gray-400">
          {template.description}
        </p>

        {/* Feature badges (first 3) */}
        <div className="mb-3 flex flex-wrap gap-1.5">
          {template.features.slice(0, 3).map((feat) => (
            <span key={feat} className={`rounded-full px-2 py-0.5 text-xs font-medium ${c.badge}`}>
              {feat}
            </span>
          ))}
          {template.features.length > 3 && (
            <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-500 dark:bg-gray-700 dark:text-gray-400">
              +{template.features.length - 3}
            </span>
          )}
        </div>

        {/* Stats */}
        <div className="flex items-center gap-3 border-t border-gray-100 pt-3 text-xs text-gray-500 dark:border-gray-700 dark:text-gray-400">
          <span className="flex items-center gap-1">
            <CubeIcon className="h-3.5 w-3.5" />
            {template.modelCount} models
          </span>
          {template.commandCount > 0 && (
            <span className="flex items-center gap-1">
              <CommandLineIcon className="h-3.5 w-3.5" />
              {template.commandCount} commands
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

export default TemplateCard;
