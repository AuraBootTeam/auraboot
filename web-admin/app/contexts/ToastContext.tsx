import {
  createContext,
  useContext,
  useState,
  useRef,
  useEffect,
  useCallback,
  useMemo,
  type ReactNode,
} from 'react';
import Toast from '~/ui/Toast';
import type { ToastEventDetail } from '~/plugins/core-designer/components/studio/services/runtime/execution/UIBridge';

interface ToastState {
  message: string;
  type: 'success' | 'error' | 'warning' | 'info';
  show: boolean;
  id: string;
  duration?: number;
}

interface ToastContextType {
  showSuccessToast: (message: string, duration?: number) => void;
  showErrorToast: (message: string, duration?: number) => void;
  showWarningToast: (message: string, duration?: number) => void;
  showInfoToast: (message: string, duration?: number) => void;
  /** Convenience method: showToast(message, type) */
  showToast: (message: string, type: 'success' | 'error' | 'warning' | 'info', duration?: number) => void;
}

const ToastContext = createContext<ToastContextType | undefined>(undefined);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastState[]>([]);
  // Use ref to maintain counter across renders without causing re-renders
  const toastIdCounter = useRef(0);

  const MAX_TOASTS = 5;

  const addToast = useCallback((message: string, type: ToastState['type'], duration?: number) => {
    // Combine counter with timestamp to ensure uniqueness even in high-frequency scenarios
    const id = `toast-${++toastIdCounter.current}-${Date.now()}`;
    setToasts((prev) => {
      const next = [...prev, { message, type, show: true, id, duration }];
      // Keep only the most recent toasts to prevent DOM bloat
      return next.length > MAX_TOASTS ? next.slice(-MAX_TOASTS) : next;
    });
  }, []);

  const removeToast = (id: string) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  };

  // Listen for imperative toast events dispatched from non-React code (UIBridge)
  useEffect(() => {
    const handler = (e: Event) => {
      const { message, variant, duration } = (e as CustomEvent<ToastEventDetail>).detail;
      addToast(message, variant, duration);
    };
    window.addEventListener('aura:toast', handler);
    return () => window.removeEventListener('aura:toast', handler);
  }, [addToast]);

  const toastMethods = useMemo(
    () => ({
      showSuccessToast: (message: string, duration?: number) => addToast(message, 'success', duration),
      showErrorToast: (message: string, duration?: number) => addToast(message, 'error', duration),
      showWarningToast: (message: string, duration?: number) => addToast(message, 'warning', duration),
      showInfoToast: (message: string, duration?: number) => addToast(message, 'info', duration),
      showToast: (message: string, type: ToastState['type'], duration?: number) =>
        addToast(message, type, duration),
    }),
    [addToast],
  );

  return (
    <ToastContext.Provider value={toastMethods}>
      {children}
      <div
        data-testid="toast-stack"
        aria-live="polite"
        className="pointer-events-none fixed top-4 left-1/2 z-50 flex w-[min(calc(100vw-2rem),28rem)] -translate-x-1/2 flex-col gap-2.5"
      >
        {toasts.map((toast) => (
          <Toast
            key={toast.id}
            message={toast.message}
            type={toast.type}
            show={toast.show}
            duration={toast.duration}
            onClose={() => removeToast(toast.id)}
          />
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToastContext() {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToastContext must be used within ToastProvider');
  }
  return context;
}

// 保持向后兼容性
export const useToast = useToastContext;
