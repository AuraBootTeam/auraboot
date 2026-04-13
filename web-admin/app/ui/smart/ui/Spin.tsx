import React from 'react';
import clsx from 'clsx';

export interface SpinProps {
  spinning: boolean;
  size?: 'small' | 'medium' | 'large';
  className?: string;
}

const sizeMap: Record<NonNullable<SpinProps['size']>, string> = {
  small: 'h-4 w-4 border-2',
  medium: 'h-6 w-6 border-2',
  large: 'h-8 w-8 border-4',
};

export const Spin: React.FC<SpinProps> = ({ spinning, size = 'medium', className }) => {
  if (!spinning) {
    return null;
  }

  const classes = clsx(
    'inline-block rounded-full border-gray-200 border-t-blue-500 animate-spin',
    sizeMap[size],
    className,
  );

  return <span className={classes} role="status" aria-live="polite" />;
};

export default Spin;
