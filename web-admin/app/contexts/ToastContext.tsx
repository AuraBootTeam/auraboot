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
import Toast from '~/components/Toast';
import type { ToastEventDetail } from '~/studio/services/runtime/execution/UIBridge';

interface ToastState {
  message: string;
  type: 'success' | 'error' | 'warning' | 'info';
  show: boolean;
  id: string;
}

interface ToastContextType {
  showSuccessToast: (message: string) => void;
  showErrorToast: (message: string) => void;
  showWarningToast: (message: string) => void;
  showInfoToast: (message: string) => void;
  /** Convenience method: showToast(message, type) */
  showToast: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void;
}

const ToastContext = createContext<ToastContextType | undefined>(undefined);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastState[]>([]);
  // Use ref to maintain counter across renders without causing re-renders
  const toastIdCounter = useRef(0);

  const MAX_TOASTS = 5;

  const addToast = useCallback((message: string, type: ToastState['type']) => {
    // Combine counter with timestamp to ensure uniqueness even in high-frequency scenarios
    const id = `toast-${++toastIdCounter.current}-${Date.now()}`;
    setToasts((prev) => {
      const next = [...prev, { message, type, show: true, id }];
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
      const { message, variant } = (e as CustomEvent<ToastEventDetail>).detail;
      addToast(message, variant);
    };
    window.addEventListener('aura:toast', handler);
    return () => window.removeEventListener('aura:toast', handler);
  }, [addToast]);

  const toastMethods = useMemo(() => ({
    showSuccessToast: (message: string) => addToast(message, 'success'),
    showErrorToast: (message: string) => addToast(message, 'error'),
    showWarningToast: (message: string) => addToast(message, 'warning'),
    showInfoToast: (message: string) => addToast(message, 'info'),
    showToast: (message: string, type: ToastState['type']) => addToast(message, type),
  }), [addToast]);

  return (
    <ToastContext.Provider value={toastMethods}>
      {children}
      {toasts.map((toast) => (
        <Toast
          key={toast.id}
          message={toast.message}
          type={toast.type}
          show={toast.show}
          onClose={() => removeToast(toast.id)}
        />
      ))}
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
