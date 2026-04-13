import {
  createContext,
  useContext,
  useState,
  useCallback,
  useRef,
  useEffect,
  type ReactNode,
} from 'react';
import ConfirmDialog from '~/ui/ConfirmDialog';
import {
  registerConfirmDialog,
  unregisterConfirmDialog,
  type ConfirmOptions,
} from '~/utils/confirmDialog';

interface ConfirmDialogContextType {
  confirm: (options: ConfirmOptions) => Promise<boolean>;
}

const ConfirmDialogContext = createContext<ConfirmDialogContextType | undefined>(undefined);

interface QueueItem {
  options: ConfirmOptions;
  resolve: (value: boolean) => void;
}

export function ConfirmDialogProvider({ children }: { children: ReactNode }) {
  const [current, setCurrent] = useState<QueueItem | null>(null);
  const queueRef = useRef<QueueItem[]>([]);
  const processingRef = useRef(false);

  const processNext = useCallback(() => {
    if (queueRef.current.length === 0) {
      processingRef.current = false;
      setCurrent(null);
      return;
    }
    processingRef.current = true;
    const next = queueRef.current.shift()!;
    setCurrent(next);
  }, []);

  const confirm = useCallback(
    (options: ConfirmOptions): Promise<boolean> => {
      return new Promise<boolean>((resolve) => {
        queueRef.current.push({ options, resolve });
        if (!processingRef.current) {
          processNext();
        }
      });
    },
    [processNext],
  );

  const handleConfirm = useCallback(() => {
    current?.resolve(true);
    processNext();
  }, [current, processNext]);

  const handleCancel = useCallback(() => {
    current?.resolve(false);
    processNext();
  }, [current, processNext]);

  // Register imperative API on mount
  useEffect(() => {
    registerConfirmDialog(confirm);
    return () => unregisterConfirmDialog();
  }, [confirm]);

  return (
    <ConfirmDialogContext.Provider value={{ confirm }}>
      {children}
      <ConfirmDialog
        open={current !== null}
        title={current?.options.title ?? '确认'}
        content={current?.options.content ?? ''}
        confirmText={current?.options.confirmText}
        cancelText={current?.options.cancelText}
        variant={current?.options.variant}
        onConfirm={handleConfirm}
        onCancel={handleCancel}
      />
    </ConfirmDialogContext.Provider>
  );
}

export function useConfirmDialog(): ConfirmDialogContextType {
  const context = useContext(ConfirmDialogContext);
  if (!context) {
    throw new Error('useConfirmDialog must be used within ConfirmDialogProvider');
  }
  return context;
}
