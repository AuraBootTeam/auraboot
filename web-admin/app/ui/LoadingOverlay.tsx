import React from 'react';

export interface LoadingOverlayProps {
  /** Whether the overlay is shown. */
  visible: boolean;
  /** Optional message; defaults to a localized "processing" hint. */
  label?: string;
  /**
   * `fixed` (default) covers the whole viewport — right for command actions whose
   * result refreshes a large part of the page. `absolute` covers the nearest
   * positioned ancestor (the caller must be `relative`) — right for scoping the
   * mask to a single block/region.
   */
  fullscreen?: boolean;
}

/**
 * Translucent backdrop + centered spinner shown while a long-running command
 * (process-fee compute, BOM export regenerate, price sourcing, …) is in flight
 * and its result is still loading.
 *
 * Why: on low-spec machines these commands can take several seconds to return
 * and re-render, during which the page otherwise looks frozen with no feedback.
 * The overlay both signals "working…" and blocks interaction (it captures
 * pointer events) so the same command can't be fired again mid-flight.
 */
export const LoadingOverlay: React.FC<LoadingOverlayProps> = ({
  visible,
  label,
  fullscreen = true,
}) => {
  if (!visible) return null;
  return (
    <div
      role="status"
      aria-live="polite"
      aria-busy="true"
      data-testid="loading-overlay"
      className={`${
        fullscreen ? 'fixed' : 'absolute'
      } inset-0 z-50 flex items-center justify-center bg-black/20 backdrop-blur-[1px]`}
    >
      <div className="rounded-card bg-panel flex items-center gap-3 px-5 py-4 shadow-lg">
        <span
          className="loading loading-spinner loading-md text-accent"
          aria-hidden="true"
        ></span>
        <span className="text-text-2 text-sm font-medium">
          {label || '处理中，请稍候…'}
        </span>
      </div>
    </div>
  );
};

export default LoadingOverlay;
