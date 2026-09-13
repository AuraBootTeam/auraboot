import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

export type ToastKind = 'success' | 'error' | 'warning' | 'info';

export interface ToastProps {
  message: string;
  type: ToastKind;
  show: boolean;
  onClose: () => void;
  duration?: number;
}

export interface ToastContextValue {
  showSuccessToast: (message: string, duration?: number) => void;
  showErrorToast: (message: string, duration?: number) => void;
  showWarningToast: (message: string, duration?: number) => void;
  showInfoToast: (message: string, duration?: number) => void;
  showToast: (message: string, type: ToastKind, duration?: number) => void;
}

interface ToastState {
  message: string;
  type: ToastKind;
  show: boolean;
  id: string;
  duration?: number;
}

const ToastContext = createContext<ToastContextValue | undefined>(undefined);

function StatusIcon({ kind, className }: { kind: ToastKind; className: string }) {
  const path = kind === 'success'
    ? 'M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z'
    : kind === 'info'
      ? 'M11.25 11.25 12 10.5m0 0 .75.75M12 10.5v6m9-4.5a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z'
      : 'M12 9v3.75m9.303 3.376c.866 1.5-.217 3.374-1.948 3.374H4.645c-1.73 0-2.813-1.874-1.948-3.374L10.05 3.376c.866-1.5 3.032-1.5 3.898 0l7.354 12.75ZM12 15.75h.008v.008H12v-.008Z';
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className={className} aria-hidden="true"><path strokeLinecap="round" strokeLinejoin="round" d={path} /></svg>;
}

function CloseIcon({ className }: { className: string }) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className={className} aria-hidden="true"><path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" /></svg>;
}

export function Toast({ message, type, show, onClose, duration = 2500 }: ToastProps) {
  const [isVisible, setIsVisible] = useState(false);
  const [isLeaving, setIsLeaving] = useState(false);
  const handleClose = useCallback(() => {
    setIsLeaving(true);
    setTimeout(() => {
      setIsVisible(false);
      onClose();
    }, 300);
  }, [onClose]);

  useEffect(() => {
    if (!show) return;
    setIsVisible(true);
    setIsLeaving(false);
    if (duration <= 0) return;
    const timer = setTimeout(handleClose, duration);
    return () => clearTimeout(timer);
  }, [show, duration, handleClose]);

  if (!show && !isVisible) return null;
  const iconClass = type === 'success'
    ? 'text-emerald-600'
    : type === 'error'
      ? 'text-red-600'
      : type === 'warning'
        ? 'text-amber-600'
        : 'text-blue-600';

  return (
    <div role="alert" aria-live="assertive" className={`bg-panel border-border pointer-events-auto w-full overflow-hidden rounded-lg border shadow-lg shadow-black/10 backdrop-blur-sm transition-all duration-300 ease-out ${isVisible && !isLeaving ? 'translate-y-0 scale-100 opacity-100' : '-translate-y-3 scale-95 opacity-0'}`}>
      <div className="px-4 py-3"><div className="flex items-start gap-3">
        <div className="mt-0.5 flex-shrink-0"><StatusIcon kind={type} className={`h-5 w-5 ${iconClass}`} /></div>
        <div className="min-w-0 flex-1"><p className="text-text text-sm leading-5 font-medium break-words">{message}</p></div>
        <button className="text-text-3 hover:bg-hover hover:text-text -mr-1 inline-flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md transition-colors duration-200 focus:ring-2 focus:ring-blue-500/30 focus:outline-none" onClick={handleClose} aria-label="Close notification">
          <CloseIcon className="h-4 w-4" />
        </button>
      </div></div>
    </div>
  );
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastState[]>([]);
  const toastIdCounter = useRef(0);
  const addToast = useCallback((message: string, type: ToastKind, duration?: number) => {
    const id = `toast-${++toastIdCounter.current}-${Date.now()}`;
    setToasts((previous) => [...previous, { message, type, show: true, id, duration }].slice(-5));
  }, []);
  const methods = useMemo<ToastContextValue>(() => ({
    showSuccessToast: (message, duration) => addToast(message, 'success', duration),
    showErrorToast: (message, duration) => addToast(message, 'error', duration),
    showWarningToast: (message, duration) => addToast(message, 'warning', duration),
    showInfoToast: (message, duration) => addToast(message, 'info', duration),
    showToast: addToast,
  }), [addToast]);

  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<{ message: string; variant: ToastKind; duration?: number }>).detail;
      addToast(detail.message, detail.variant, detail.duration);
    };
    window.addEventListener('aura:toast', handler);
    return () => window.removeEventListener('aura:toast', handler);
  }, [addToast]);

  return (
    <ToastContext.Provider value={methods}>
      {children}
      <div data-testid="toast-stack" aria-live="polite" className="pointer-events-none fixed top-4 left-1/2 z-50 flex w-[min(calc(100vw-2rem),28rem)] -translate-x-1/2 flex-col gap-2.5">
        {toasts.map((toast) => <Toast key={toast.id} {...toast} onClose={() => setToasts((items) => items.filter((item) => item.id !== toast.id))} />)}
      </div>
    </ToastContext.Provider>
  );
}

export function useToastContext(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) throw new Error('useToastContext must be used within ToastProvider');
  return context;
}

export const useToast = useToastContext;
