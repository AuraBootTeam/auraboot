import { useState, useEffect, useCallback } from 'react';
import {
  XMarkIcon,
  ExclamationTriangleIcon,
  CheckCircleIcon,
  InformationCircleIcon,
} from '@heroicons/react/24/outline';

interface ToastProps {
  message: string;
  type: 'success' | 'error' | 'warning' | 'info';
  show: boolean;
  onClose: () => void;
  duration?: number;
}

export default function Toast({ message, type, show, onClose, duration = 2500 }: ToastProps) {
  const [isVisible, setIsVisible] = useState(false);
  const [isLeaving, setIsLeaving] = useState(false);

  const handleClose = useCallback(() => {
    setIsLeaving(true);
    setTimeout(() => {
      setIsVisible(false);
      onClose();
    }, 300); // 等待退出动画完成
  }, [onClose]);

  useEffect(() => {
    if (show) {
      setIsVisible(true);
      setIsLeaving(false);

      if (duration > 0) {
        const timer = setTimeout(() => {
          handleClose();
        }, duration);
        return () => clearTimeout(timer);
      }
    }
  }, [show, duration, handleClose]);

  const getIcon = () => {
    const iconClass = `h-5 w-5 ${getStyles().icon}`;
    switch (type) {
      case 'success':
        return <CheckCircleIcon className={iconClass} />;
      case 'error':
        return <ExclamationTriangleIcon className={iconClass} />;
      case 'warning':
        return <ExclamationTriangleIcon className={iconClass} />;
      case 'info':
        return <InformationCircleIcon className={iconClass} />;
    }
  };

  const getStyles = () => {
    switch (type) {
      case 'success':
        return { icon: 'text-emerald-600' };
      case 'error':
        return { icon: 'text-red-600' };
      case 'warning':
        return { icon: 'text-amber-600' };
      case 'info':
        return { icon: 'text-blue-600' };
    }
  };

  if (!show && !isVisible) return null;

  return (
    <div
      role="alert"
      aria-live="assertive"
      className={`bg-panel border-border pointer-events-auto w-full overflow-hidden rounded-lg border shadow-lg shadow-black/10 backdrop-blur-sm transition-all duration-300 ease-out ${
        isVisible && !isLeaving
          ? 'translate-y-0 scale-100 opacity-100'
          : '-translate-y-3 scale-95 opacity-0'
      } `}
    >
      <div className="px-4 py-3">
        <div className="flex items-start gap-3">
          <div className="mt-0.5 flex-shrink-0">{getIcon()}</div>
          <div className="min-w-0 flex-1">
            <p className="text-text text-sm leading-5 font-medium break-words">{message}</p>
          </div>
          <button
            className="text-text-3 hover:bg-hover hover:text-text -mr-1 inline-flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md transition-colors duration-200 focus:ring-2 focus:ring-blue-500/30 focus:outline-none"
            onClick={handleClose}
            aria-label="Close notification"
          >
            <XMarkIcon className="h-4 w-4" />
          </button>
        </div>
      </div>

    </div>
  );
}
