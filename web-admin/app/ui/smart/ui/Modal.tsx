import React from 'react';
import clsx from 'clsx';

export interface ModalProps {
  open: boolean;
  title?: React.ReactNode;
  footer?: React.ReactNode;
  onCancel?: () => void;
  className?: string;
  children?: React.ReactNode;
}

export const Modal: React.FC<ModalProps> = ({
  open,
  title,
  footer,
  onCancel,
  className,
  children,
}) => {
  if (!open) {
    return null;
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className={clsx('rounded-card bg-panel w-full max-w-2xl shadow-lg', className)}>
        <div className="border-border flex items-center justify-between border-b px-4 py-3">
          <div className="text-text text-lg font-semibold">{title}</div>
          <button
            type="button"
            className="text-text-3 hover:text-text-2"
            onClick={onCancel}
            aria-label="Close"
          >
            ×
          </button>
        </div>
        <div className="max-h-[60vh] overflow-y-auto p-4">{children}</div>
        {footer && (
          <div className="rounded-b-card border-border bg-subtle border-t px-4 py-3">{footer}</div>
        )}
      </div>
    </div>
  );
};

export default Modal;
