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
      <div className={clsx('w-full max-w-2xl rounded-lg bg-white shadow-lg', className)}>
        <div className="flex items-center justify-between border-b border-gray-200 px-4 py-3">
          <div className="text-lg font-semibold text-gray-900">{title}</div>
          <button
            type="button"
            className="text-gray-400 hover:text-gray-500"
            onClick={onCancel}
            aria-label="Close"
          >
            ×
          </button>
        </div>
        <div className="max-h-[60vh] overflow-y-auto p-4">{children}</div>
        {footer && (
          <div className="rounded-b-lg border-t border-gray-200 bg-gray-50 px-4 py-3">{footer}</div>
        )}
      </div>
    </div>
  );
};

export default Modal;
