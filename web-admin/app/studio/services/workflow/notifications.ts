export type NotificationType = 'success' | 'error' | 'info' | 'warning';

export interface NotificationPayload {
  type: NotificationType;
  message: string;
  description?: string;
  duration?: number;
}

export type NotificationSubscriber = (payload: NotificationPayload) => void;

const subscribers = new Set<NotificationSubscriber>();

function emitNotification(payload: NotificationPayload) {
  if (subscribers.size === 0) {
    if (payload.type === 'error') {
      console.error('[notification]', payload.message, payload.description);
    } else {
      console.log('[notification]', payload.message, payload.description);
    }
    return;
  }

  subscribers.forEach((subscriber) => subscriber(payload));
}

export const notificationService = {
  subscribe(subscriber: NotificationSubscriber) {
    subscribers.add(subscriber);
    return () => {
      subscribers.delete(subscriber);
    };
  },
  notify(payload: NotificationPayload) {
    emitNotification(payload);
  },
  success(message: string, description?: string) {
    emitNotification({ type: 'success', message, description });
  },
  error(message: string, description?: string) {
    emitNotification({ type: 'error', message, description });
  },
  info(message: string, description?: string) {
    emitNotification({ type: 'info', message, description });
  },
  warning(message: string, description?: string) {
    emitNotification({ type: 'warning', message, description });
  },
};
