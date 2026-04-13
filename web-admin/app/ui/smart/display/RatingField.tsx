/**
 * RatingField Component
 *
 * A star rating field supporting 1-5 (or custom max) rating values.
 * Supports both edit and read-only modes.
 */

import React, { useState, useCallback } from 'react';
import { cn } from '~/utils/cn';

export interface RatingFieldProps {
  /** Current rating value */
  value?: number;
  /** Callback when rating changes */
  onChange?: (value: number) => void;
  /** Maximum rating value */
  maxRating?: number;
  /** Whether the field is read-only */
  readOnly?: boolean;
  /** Star size in pixels */
  size?: number;
  /** Custom CSS class */
  className?: string;
}

/**
 * RatingField - Star rating input/display
 */
export const RatingField: React.FC<RatingFieldProps> = ({
  value = 0,
  onChange,
  maxRating = 5,
  readOnly = false,
  size = 20,
  className,
}) => {
  const [hoverValue, setHoverValue] = useState<number | null>(null);

  const handleClick = useCallback(
    (rating: number) => {
      if (readOnly) return;
      // Toggle off if clicking the same value
      onChange?.(rating === value ? 0 : rating);
    },
    [readOnly, value, onChange],
  );

  const displayValue = hoverValue ?? value;

  return (
    <div
      className={cn('inline-flex items-center gap-0.5', className)}
      onMouseLeave={() => !readOnly && setHoverValue(null)}
    >
      {Array.from({ length: maxRating }, (_, i) => {
        const rating = i + 1;
        const filled = rating <= displayValue;

        return (
          <button
            key={rating}
            type="button"
            onClick={() => handleClick(rating)}
            onMouseEnter={() => !readOnly && setHoverValue(rating)}
            disabled={readOnly}
            className={cn(
              'transition-colors duration-100',
              readOnly ? 'cursor-default' : 'cursor-pointer hover:scale-110',
            )}
            style={{ width: size, height: size }}
            aria-label={`${rating} star${rating !== 1 ? 's' : ''}`}
          >
            <svg
              viewBox="0 0 24 24"
              fill={filled ? 'currentColor' : 'none'}
              stroke="currentColor"
              strokeWidth={filled ? 0 : 1.5}
              className={cn('h-full w-full', filled ? 'text-yellow-400' : 'text-gray-300')}
            >
              <path d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z" />
            </svg>
          </button>
        );
      })}
      {!readOnly && value > 0 && (
        <span className="ml-1 text-xs text-gray-500">
          {value}/{maxRating}
        </span>
      )}
    </div>
  );
};

export default RatingField;
