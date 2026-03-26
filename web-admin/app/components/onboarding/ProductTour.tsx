/**
 * ProductTour — Step-by-step guided tour with overlay cutout.
 *
 * Renders a semi-transparent overlay with a rectangular cutout around the
 * target element, plus a popover with navigation controls.
 * Uses createPortal to render at the document body level.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useOnboarding } from './OnboardingProvider';
import { TOUR_STEPS, type TourPlacement } from './tourSteps';

// ---------------------------------------------------------------------------
// Geometry helpers
// ---------------------------------------------------------------------------

interface Rect {
  top: number;
  left: number;
  width: number;
  height: number;
  bottom: number;
  right: number;
}

const PADDING = 8;
const POPOVER_GAP = 12;
const POPOVER_WIDTH = 340;

function getTargetRect(selector: string): Rect | null {
  // Support comma-separated selectors (fallback chain)
  const selectors = selector.split(',').map((s) => s.trim());
  for (const sel of selectors) {
    const el = document.querySelector(sel);
    if (el) {
      const r = el.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) {
        return {
          top: r.top,
          left: r.left,
          width: r.width,
          height: r.height,
          bottom: r.bottom,
          right: r.right,
        };
      }
    }
  }
  return null;
}

function computePopoverPosition(
  rect: Rect,
  placement: TourPlacement,
): { top: number; left: number; actualPlacement: TourPlacement } {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  let actualPlacement = placement;

  // Check if there's enough space for the preferred placement
  if (placement === 'bottom' && rect.bottom + POPOVER_GAP + 200 > vh) {
    actualPlacement = 'top';
  } else if (placement === 'top' && rect.top - POPOVER_GAP - 200 < 0) {
    actualPlacement = 'bottom';
  } else if (placement === 'right' && rect.right + POPOVER_GAP + POPOVER_WIDTH > vw) {
    actualPlacement = 'left';
  } else if (placement === 'left' && rect.left - POPOVER_GAP - POPOVER_WIDTH < 0) {
    actualPlacement = 'right';
  }

  let top = 0;
  let left = 0;

  switch (actualPlacement) {
    case 'bottom':
      top = rect.bottom + POPOVER_GAP;
      left = rect.left + rect.width / 2 - POPOVER_WIDTH / 2;
      break;
    case 'top':
      top = rect.top - POPOVER_GAP;
      left = rect.left + rect.width / 2 - POPOVER_WIDTH / 2;
      break;
    case 'right':
      top = rect.top + rect.height / 2;
      left = rect.right + POPOVER_GAP;
      break;
    case 'left':
      top = rect.top + rect.height / 2;
      left = rect.left - POPOVER_GAP - POPOVER_WIDTH;
      break;
  }

  // Clamp within viewport
  left = Math.max(12, Math.min(left, vw - POPOVER_WIDTH - 12));
  top = Math.max(12, top);

  return { top, left, actualPlacement };
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function ProductTour() {
  const { state, nextStep, prevStep, endTour } = useOnboarding();
  const [targetRect, setTargetRect] = useState<Rect | null>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  const { tourActive, tourStep } = state;
  const currentStep = TOUR_STEPS[tourStep];
  const isFirst = tourStep === 0;
  const isLast = tourStep === TOUR_STEPS.length - 1;

  // Measure target element position
  const measureTarget = useCallback(() => {
    if (!currentStep) return;
    const rect = getTargetRect(currentStep.target);
    setTargetRect(rect);

    // Scroll into view if needed
    if (rect) {
      const el = document.querySelector(currentStep.target.split(',')[0].trim());
      if (el) {
        const elRect = el.getBoundingClientRect();
        if (elRect.top < 0 || elRect.bottom > window.innerHeight) {
          el.scrollIntoView({ behavior: 'smooth', block: 'center' });
          // Re-measure after scroll
          setTimeout(() => {
            const newRect = getTargetRect(currentStep.target);
            setTargetRect(newRect);
          }, 400);
        }
      }
    }
  }, [currentStep]);

  useEffect(() => {
    if (!tourActive) return;
    measureTarget();

    // Re-measure on resize/scroll
    const handleUpdate = () => measureTarget();
    window.addEventListener('resize', handleUpdate);
    window.addEventListener('scroll', handleUpdate, true);
    return () => {
      window.removeEventListener('resize', handleUpdate);
      window.removeEventListener('scroll', handleUpdate, true);
    };
  }, [tourActive, tourStep, measureTarget]);

  // Keyboard navigation
  useEffect(() => {
    if (!tourActive) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      switch (e.key) {
        case 'ArrowRight':
          e.preventDefault();
          if (!isLast) nextStep();
          else endTour();
          break;
        case 'ArrowLeft':
          e.preventDefault();
          if (!isFirst) prevStep();
          break;
        case 'Escape':
          e.preventDefault();
          endTour();
          break;
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [tourActive, isFirst, isLast, nextStep, prevStep, endTour]);

  if (!tourActive || !currentStep) return null;

  // Fallback rect when target not found — center of screen
  const rect = targetRect ?? {
    top: window.innerHeight / 2 - 50,
    left: window.innerWidth / 2 - 50,
    width: 100,
    height: 100,
    bottom: window.innerHeight / 2 + 50,
    right: window.innerWidth / 2 + 50,
  };

  const {
    top: popTop,
    left: popLeft,
    actualPlacement,
  } = computePopoverPosition(rect, currentStep.placement);

  // For top placement, anchor bottom of popover to the computed position
  const popoverStyle: React.CSSProperties = {
    position: 'fixed',
    left: popLeft,
    width: POPOVER_WIDTH,
    zIndex: 10002,
    ...(actualPlacement === 'top' ? { bottom: window.innerHeight - popTop } : { top: popTop }),
  };

  const overlay = (
    <div className="fixed inset-0 z-[10000]" data-testid="product-tour-overlay">
      {/* SVG overlay with cutout */}
      <svg className="absolute inset-0 h-full w-full" style={{ pointerEvents: 'none' }}>
        <defs>
          <mask id="tour-mask">
            <rect x="0" y="0" width="100%" height="100%" fill="white" />
            {targetRect && (
              <rect
                x={rect.left - PADDING}
                y={rect.top - PADDING}
                width={rect.width + PADDING * 2}
                height={rect.height + PADDING * 2}
                rx="8"
                fill="black"
              />
            )}
          </mask>
        </defs>
        <rect
          x="0"
          y="0"
          width="100%"
          height="100%"
          fill="rgba(0,0,0,0.5)"
          mask="url(#tour-mask)"
          style={{ pointerEvents: 'auto' }}
          onClick={endTour}
        />
      </svg>

      {/* Highlight ring around target */}
      {targetRect && (
        <div
          className="absolute rounded-lg ring-2 ring-blue-400 ring-offset-2 transition-all duration-300"
          style={{
            top: rect.top - PADDING,
            left: rect.left - PADDING,
            width: rect.width + PADDING * 2,
            height: rect.height + PADDING * 2,
            zIndex: 10001,
            pointerEvents: 'none',
          }}
        />
      )}

      {/* Popover */}
      <div
        ref={popoverRef}
        style={popoverStyle}
        className="animate-in fade-in rounded-xl border border-gray-200 bg-white p-5 shadow-2xl duration-200 dark:border-gray-700 dark:bg-gray-800"
        data-testid="product-tour-popover"
      >
        {/* Step counter */}
        <div className="mb-3 flex items-center justify-between">
          <span className="text-xs font-medium text-gray-400 dark:text-gray-500">
            {tourStep + 1} / {TOUR_STEPS.length}
          </span>
          <button
            onClick={endTour}
            className="text-xs text-gray-400 transition-colors hover:text-gray-600 dark:hover:text-gray-300"
            data-testid="tour-skip"
          >
            Skip tour
          </button>
        </div>

        {/* Content */}
        <h3 className="mb-2 text-base font-semibold text-gray-900 dark:text-white">
          {currentStep.title}
        </h3>
        <p className="mb-4 text-sm leading-relaxed text-gray-600 dark:text-gray-400">
          {currentStep.content}
        </p>

        {/* Progress dots */}
        <div className="mb-4 flex items-center justify-center gap-1.5">
          {TOUR_STEPS.map((_, i) => (
            <div
              key={i}
              className={`h-1.5 rounded-full transition-all duration-200 ${
                i === tourStep
                  ? 'w-4 bg-blue-500'
                  : i < tourStep
                    ? 'w-1.5 bg-blue-300 dark:bg-blue-600'
                    : 'w-1.5 bg-gray-200 dark:bg-gray-600'
              }`}
            />
          ))}
        </div>

        {/* Navigation buttons */}
        <div className="flex items-center justify-between">
          <button
            onClick={prevStep}
            disabled={isFirst}
            className="px-3 py-1.5 text-sm text-gray-600 transition-colors hover:text-gray-900 disabled:cursor-not-allowed disabled:opacity-30 dark:text-gray-400 dark:hover:text-white"
            data-testid="tour-prev"
          >
            Previous
          </button>
          <button
            onClick={isLast ? endTour : nextStep}
            className="rounded-lg bg-blue-600 px-4 py-1.5 text-sm font-medium text-white transition-colors hover:bg-blue-700"
            data-testid="tour-next"
          >
            {isLast ? 'Finish' : 'Next'}
          </button>
        </div>
      </div>
    </div>
  );

  return createPortal(overlay, document.body);
}
