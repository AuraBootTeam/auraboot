import { useState, useEffect, useRef } from 'react';
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

export default function Toast({ message, type, show, onClose, duration = 4000 }: ToastProps) {
  const [isVisible, setIsVisible] = useState(false);
  const [isLeaving, setIsLeaving] = useState(false);
  const [progressStarted, setProgressStarted] = useState(false);
  const progressTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => {
    if (show) {
      setIsVisible(true);
      setIsLeaving(false);
      setProgressStarted(false);

      // Delay progress start by one frame so CSS transition triggers (22.5 fix)
      progressTimerRef.current = setTimeout(() => setProgressStarted(true), 20);

      if (duration > 0) {
        const timer = setTimeout(() => {
          handleClose();
        }, duration);
        return () => {
          clearTimeout(timer);
          clearTimeout(progressTimerRef.current);
        };
      }
      return () => clearTimeout(progressTimerRef.current);
    }
  }, [show, duration]);

  const handleClose = () => {
    setIsLeaving(true);
    setTimeout(() => {
      setIsVisible(false);
      onClose();
    }, 300); // 等待退出动画完成
  };

  const getIcon = () => {
    switch (type) {
      case 'success':
        return <CheckCircleIcon className="h-5 w-5 text-white" />;
      case 'error':
        return <ExclamationTriangleIcon className="h-5 w-5 text-white" />;
      case 'warning':
        return <ExclamationTriangleIcon className="h-5 w-5 text-white" />;
      case 'info':
        return <InformationCircleIcon className="h-5 w-5 text-white" />;
    }
  };

  const getStyles = () => {
    switch (type) {
      case 'success':
        return {
          bg: 'bg-emerald-500',
          shadow: 'shadow-lg shadow-emerald-500/25',
        };
      case 'error':
        return {
          bg: 'bg-red-500',
          shadow: 'shadow-lg shadow-red-500/25',
        };
      case 'warning':
        return {
          bg: 'bg-amber-500',
          shadow: 'shadow-lg shadow-amber-500/25',
        };
      case 'info':
        return {
          bg: 'bg-blue-500',
          shadow: 'shadow-lg shadow-blue-500/25',
        };
    }
  };

  if (!show && !isVisible) return null;

  const styles = getStyles();

  return (
    <div className="pointer-events-none fixed top-4 left-1/2 z-50 -translate-x-1/2 transform">
      <div
        role="alert"
        aria-live="assertive"
        className={`pointer-events-auto w-full max-w-md min-w-80 ${styles.bg} ${styles.shadow} transform rounded-lg backdrop-blur-sm transition-all duration-300 ease-out ${
          isVisible && !isLeaving
            ? 'translate-y-0 scale-100 opacity-100'
            : '-translate-y-full scale-95 opacity-0'
        } `}
      >
        <div className="px-4 py-3">
          <div className="flex items-center">
            <div className="flex-shrink-0">{getIcon()}</div>
            <div className="ml-3 flex-1">
              <p className="text-sm leading-5 font-medium text-white">{message}</p>
            </div>
            <div className="ml-4 flex-shrink-0">
              <button
                className="inline-flex rounded-md p-1 text-white/70 transition-colors duration-200 hover:bg-white/10 hover:text-white focus:ring-2 focus:ring-white/30 focus:outline-none"
                onClick={handleClose}
              >
                <XMarkIcon className="h-4 w-4" />
              </button>
            </div>
          </div>
        </div>

        {/* 进度条 */}
        {duration > 0 && (
          <div className="h-1 overflow-hidden rounded-b-lg bg-white/20">
            <div
              className="h-full bg-white/40 transition-all ease-linear"
              style={{
                width: progressStarted && !isLeaving ? '0%' : '100%',
                transitionDuration: progressStarted && !isLeaving ? `${duration}ms` : '0ms',
              }}
            />
          </div>
        )}
      </div>
    </div>
  );
}
