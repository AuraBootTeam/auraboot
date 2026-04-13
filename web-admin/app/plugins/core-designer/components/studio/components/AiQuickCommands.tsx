/**
 * AiQuickCommands — Preset quick action buttons for AI page generation
 *
 * Displays a row of predefined commands the user can click
 * to quickly instruct the AI assistant without typing.
 *
 * @since 4.2.0
 */

import React from 'react';

export interface QuickCommand {
  label: string;
  icon: string;
  prompt: string;
}

const DEFAULT_COMMANDS: QuickCommand[] = [
  {
    label: 'Add Chart',
    icon: '\u{1F4CA}',
    prompt: 'Add a bar chart block to this page that visualizes the key metrics from the model data.',
  },
  {
    label: 'Add Filters',
    icon: '\u{1F50D}',
    prompt: 'Add a filters block with the most useful filter fields for this model.',
  },
  {
    label: 'Optimize Layout',
    icon: '\u{2728}',
    prompt: 'Optimize the current page layout for better readability and visual hierarchy. Adjust colSpan values and block ordering.',
  },
  {
    label: 'Add Stat Cards',
    icon: '\u{1F4C8}',
    prompt: 'Add 3 stat-card blocks showing the most important KPI metrics for this model.',
  },
];

export interface AiQuickCommandsProps {
  onCommand: (prompt: string) => void;
  disabled?: boolean;
  commands?: QuickCommand[];
}

export const AiQuickCommands: React.FC<AiQuickCommandsProps> = ({
  onCommand,
  disabled = false,
  commands = DEFAULT_COMMANDS,
}) => {
  return (
    <div className="flex flex-wrap gap-2 px-4 py-2" data-testid="ai-quick-commands">
      {commands.map((cmd) => (
        <button
          key={cmd.label}
          onClick={() => onCommand(cmd.prompt)}
          disabled={disabled}
          className="flex items-center gap-1.5 rounded-full border border-gray-200 bg-white px-3 py-1.5 text-xs text-gray-600 transition-colors hover:border-purple-300 hover:bg-purple-50 hover:text-purple-700 disabled:cursor-not-allowed disabled:opacity-40"
          data-testid={`ai-quick-cmd-${cmd.label.toLowerCase().replace(/\s+/g, '-')}`}
        >
          <span>{cmd.icon}</span>
          <span>{cmd.label}</span>
        </button>
      ))}
    </div>
  );
};

export default AiQuickCommands;
