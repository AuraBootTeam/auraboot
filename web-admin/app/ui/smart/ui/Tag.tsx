import React from 'react';
import clsx from 'clsx';

export interface TagProps {
  children: React.ReactNode;
  color?: 'default' | 'blue' | 'red' | 'green' | 'gray';
  size?: 'small' | 'medium';
  className?: string;
}

const colorClassMap: Record<NonNullable<TagProps['color']>, string> = {
  default: 'bg-gray-100 text-gray-700',
  blue: 'bg-blue-100 text-blue-700',
  red: 'bg-red-100 text-red-700',
  green: 'bg-green-100 text-green-700',
  gray: 'bg-gray-200 text-gray-800',
};

const sizeClassMap: Record<NonNullable<TagProps['size']>, string> = {
  small: 'px-2 py-0.5 text-xs',
  medium: 'px-3 py-1 text-sm',
};

export const Tag: React.FC<TagProps> = ({
  children,
  color = 'default',
  size = 'small',
  className,
}) => {
  const classes = clsx(
    'inline-flex items-center rounded-full font-medium',
    colorClassMap[color],
    sizeClassMap[size],
    className,
  );

  return <span className={classes}>{children}</span>;
};

export default Tag;
