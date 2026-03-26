/**
 * Debug Panel
 *
 * Note: This component has been simplified after removing the old i18n debugger.
 * It now serves as a placeholder for future debugging features.
 */

import React from 'react';

interface DebugPanelProps {
  position?: 'top-right' | 'bottom-right' | 'bottom-left' | 'top-left';
  minimized?: boolean;
}

/**
 * Development environment debug panel
 * Simplified version - old i18n debugger has been removed
 */
export const DebugPanel: React.FC<DebugPanelProps> = () => {
  // Only show in development
  if (process.env.NODE_ENV !== 'development') {
    return null;
  }

  // Simplified version - no debugging UI for now
  // You can add new debugging features here as needed
  return null;
};

export default DebugPanel;
