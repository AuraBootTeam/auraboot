import { useState, useCallback } from 'react';
import { get, put } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';

/**
 * Notification preference DTO matching backend NotificationPreferenceDTO.
 */
export interface NotificationPreference {
  id: number | null;
  channel: string;
  category: string;
  enabled: boolean;
}

/**
 * All known notification channels.
 */
export const CHANNELS = ['in_app', 'email', 'wechat_work', 'dingtalk', 'slack'] as const;

/**
 * All known notification categories.
 */
export const CATEGORIES = ['business', 'approval', 'system', 'alert'] as const;

/**
 * Channel i18n keys for display labels.
 * Usage: t(CHANNEL_I18N_KEYS['in_app']) → localized label
 */
export const CHANNEL_I18N_KEYS: Record<string, string> = {
  IN_APP: 'notification.channel.in_app',
  EMAIL: 'notification.channel.email',
  WECHAT_WORK: 'notification.channel.wechat_work',
  DINGTALK: 'notification.channel.dingtalk',
  SLACK: 'notification.channel.slack',
};

/**
 * Category i18n keys for display labels.
 * Usage: t(CATEGORY_I18N_KEYS['business']) → localized label
 */
export const CATEGORY_I18N_KEYS: Record<string, string> = {
  BUSINESS: 'notification.category.business',
  APPROVAL: 'notification.category.approval',
  SYSTEM: 'notification.category.system',
  ALERT: 'notification.category.alert',
};

/**
 * @deprecated Use CHANNEL_I18N_KEYS with t() instead
 */
export const CHANNEL_LABELS = CHANNEL_I18N_KEYS;

/**
 * @deprecated Use CATEGORY_I18N_KEYS with t() instead
 */
export const CATEGORY_LABELS = CATEGORY_I18N_KEYS;

/**
 * Hook for managing user notification preferences.
 *
 * Backend uses opt-out model: all channel+category combos are enabled by default.
 * Only explicit records with enabled=false represent opt-outs.
 * SYSTEM + IN_APP is always forced on by the backend.
 */
export function useNotificationPreferences() {
  const [preferences, setPreferences] = useState<NotificationPreference[]>([]);
  const [loading, setLoading] = useState(false);
  const [updating, setUpdating] = useState<string | null>(null); // "channel:category" key
  const { showErrorToast } = useToastContext();

  /**
   * Build a complete matrix of all channel+category combinations.
   * Backend only returns explicit records — missing combos default to enabled.
   */
  const buildMatrix = useCallback((serverPrefs: NotificationPreference[]): Map<string, boolean> => {
    const matrix = new Map<string, boolean>();

    // Initialize all combos as enabled (opt-out model)
    for (const channel of CHANNELS) {
      for (const category of CATEGORIES) {
        matrix.set(`${channel}:${category}`, true);
      }
    }

    // Apply explicit overrides from server
    for (const pref of serverPrefs) {
      matrix.set(`${pref.channel}:${pref.category}`, pref.enabled);
    }

    // SYSTEM + IN_APP is always forced on
    matrix.set('IN_APP:SYSTEM', true);

    return matrix;
  }, []);

  /**
   * Fetch all notification preferences for the current user.
   */
  const fetchPreferences = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<NotificationPreference[]>('/api/notifications/preferences');

      if (ResultHelper.isSuccess(result) && result.data) {
        setPreferences(result.data);
      } else {
        showErrorToast(result.desc || 'notification.preferences.load_failed');
      }
    } catch (error) {
      console.error('Failed to fetch notification preferences:', error);
      showErrorToast('notification.preferences.load_failed');
    } finally {
      setLoading(false);
    }
  }, [showErrorToast]);

  /**
   * Update a single preference and refresh local state optimistically.
   */
  const updatePreference = useCallback(
    async (channel: string, category: string, enabled: boolean) => {
      const key = `${channel}:${category}`;
      setUpdating(key);

      // Optimistic update
      setPreferences((prev) => {
        const existing = prev.find((p) => p.channel === channel && p.category === category);
        if (existing) {
          return prev.map((p) =>
            p.channel === channel && p.category === category ? { ...p, enabled } : p,
          );
        }
        return [...prev, { id: null, channel, category, enabled }];
      });

      try {
        const result = await put<void>('/api/notifications/preferences', {
          channel,
          category,
          enabled,
        });

        if (!ResultHelper.isSuccess(result)) {
          // Revert optimistic update on failure
          setPreferences((prev) =>
            prev.map((p) =>
              p.channel === channel && p.category === category ? { ...p, enabled: !enabled } : p,
            ),
          );
          showErrorToast(result.desc || 'notification.preferences.update_failed');
        }
      } catch (error) {
        console.error('Failed to update notification preference:', error);
        // Revert optimistic update
        setPreferences((prev) =>
          prev.map((p) =>
            p.channel === channel && p.category === category ? { ...p, enabled: !enabled } : p,
          ),
        );
        showErrorToast('notification.preferences.update_failed');
      } finally {
        setUpdating(null);
      }
    },
    [showErrorToast],
  );

  /**
   * Check if a specific channel+category is enabled.
   */
  const isEnabled = useCallback(
    (channel: string, category: string): boolean => {
      // SYSTEM + IN_APP is always on
      if (channel === 'in_app' && category === 'system') {
        return true;
      }
      const matrix = buildMatrix(preferences);
      return matrix.get(`${channel}:${category}`) ?? true;
    },
    [preferences, buildMatrix],
  );

  /**
   * Check if a specific channel+category is forced (cannot be toggled).
   */
  const isForced = useCallback((channel: string, category: string): boolean => {
    return channel === 'in_app' && category === 'system';
  }, []);

  return {
    preferences,
    loading,
    updating,
    fetchPreferences,
    updatePreference,
    isEnabled,
    isForced,
  };
}
