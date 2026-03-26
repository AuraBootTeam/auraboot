import React from 'react';
import { useEntitlement } from '../contexts/EntitlementContext';

export function EntitlementWarningBanner() {
  const { enabled, getWarnings } = useEntitlement();

  if (!enabled) return null;

  const warnings = getWarnings();
  if (warnings.length === 0) return null;

  return (
    <div className="space-y-1">
      {warnings.map((w) => (
        <div
          key={w.pluginId}
          className={`flex items-center gap-2 px-4 py-2 text-sm ${
            w.warning?.severity === 'warning'
              ? 'border-b border-yellow-200 bg-yellow-50 text-yellow-800'
              : 'border-b border-blue-200 bg-blue-50 text-blue-800'
          }`}
        >
          <span className="font-medium">{w.pluginId}</span>
          <span>
            {w.warning?.code === 'license_in_grace'
              ? `License expired, in grace period`
              : w.warning?.code === 'trial_ending'
                ? `Trial ending soon`
                : `License expiring soon`}
          </span>
          {w.graceUntil && (
            <span className="text-xs opacity-70">
              (until {new Date(w.graceUntil).toLocaleDateString()})
            </span>
          )}
          {w.expiresAt && !w.graceUntil && (
            <span className="text-xs opacity-70">
              (expires {new Date(w.expiresAt).toLocaleDateString()})
            </span>
          )}
        </div>
      ))}
    </div>
  );
}
