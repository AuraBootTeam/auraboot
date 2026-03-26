/**
 * Schema Action Region Renderer
 *
 * Renders the action toolbar section of a schema-driven page,
 * including the page title and action buttons.
 */

import React from 'react';
import type { ActionRendererProps, ActionDefinition, LocalizedText } from './types';

/**
 * Action button component
 */
function ActionButton({
  action,
  onAction,
  getLocalizedText,
}: {
  action: ActionDefinition;
  onAction?: (action: ActionDefinition) => void;
  getLocalizedText: (text: LocalizedText, fallback?: string) => string;
}) {
  const isPrimary = action.type === 'primary';

  return (
    <button
      onClick={() => onAction?.(action)}
      className={`rounded-md px-4 py-2 text-sm font-medium ${
        isPrimary
          ? 'bg-blue-600 text-white hover:bg-blue-700'
          : 'border border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
      }`}
    >
      {getLocalizedText(action.label, action.code)}
    </button>
  );
}

/**
 * Schema Action Region Renderer
 *
 * Renders a toolbar with page title and action buttons.
 */
export function SchemaActionRenderer({
  region,
  title,
  onAction,
  getLocalizedText,
}: ActionRendererProps) {
  if (!region.actions || region.actions.length === 0) {
    return null;
  }

  return (
    <div className="mb-6 flex items-center justify-between">
      <h2 className="text-lg font-medium text-gray-900">{getLocalizedText(title, 'Data List')}</h2>
      <div className="flex space-x-2">
        {region.actions.map((action, index) => (
          <ActionButton
            key={action.code || index}
            action={action}
            onAction={onAction}
            getLocalizedText={getLocalizedText}
          />
        ))}
      </div>
    </div>
  );
}

export default SchemaActionRenderer;
