import React from 'react';
import clsx from 'clsx';
import type { ButtonProps } from '~/studio/domain/schema/smart-components';

const sizeStyles: Record<NonNullable<ButtonProps['size']>, string> = {
  small: 'px-2 py-1 text-sm',
  medium: 'px-3 py-2 text-base',
  large: 'px-4 py-3 text-lg',
};

const variantStyles: Record<NonNullable<ButtonProps['variant']>, string> = {
  default: 'bg-white text-gray-700 border border-gray-300 hover:bg-gray-50',
  primary: 'bg-blue-600 text-white hover:bg-blue-700',
  secondary: 'bg-gray-600 text-white hover:bg-gray-700',
  danger: 'bg-red-600 text-white hover:bg-red-700',
  ghost: 'bg-transparent text-gray-700 hover:bg-gray-100',
  link: 'bg-transparent text-blue-600 hover:underline',
};

export const Button: React.FC<ButtonProps> = ({
  type = 'button',
  variant = 'default',
  size = 'medium',
  loading = false,
  disabled = false,
  icon,
  iconPosition = 'left',
  block = false,
  href,
  target,
  className,
  children,
  onClick,
  ...rest
}) => {
  const content = (
    <>
      {icon && iconPosition === 'left' && <span className="mr-2 flex items-center">{icon}</span>}
      <span>{children}</span>
      {icon && iconPosition === 'right' && <span className="ml-2 flex items-center">{icon}</span>}
    </>
  );

  const sharedProps = {
    className: clsx(
      'inline-flex items-center justify-center rounded-md font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:opacity-60 disabled:cursor-not-allowed',
      sizeStyles[size],
      variantStyles[variant],
      block && 'w-full',
      className,
    ),
    'aria-disabled': disabled || loading,
    ...rest,
  };

  if (href) {
    return (
      <a
        {...sharedProps}
        href={href}
        target={target}
        rel={target === '_blank' ? 'noopener noreferrer' : undefined}
        onClick={(event) => {
          if (disabled || loading) {
            event.preventDefault();
            return;
          }
          onClick?.(event as unknown as React.MouseEvent);
        }}
      >
        {content}
      </a>
    );
  }

  return (
    <button
      {...sharedProps}
      type={type}
      disabled={disabled || loading}
      onClick={(event) => {
        if (disabled || loading) {
          event.preventDefault();
          return;
        }
        onClick?.(event);
      }}
    >
      {loading && (
        <svg
          className="mr-2 h-4 w-4 animate-spin"
          viewBox="0 0 24 24"
          fill="none"
          role="presentation"
        >
          <circle
            className="opacity-25"
            cx="12"
            cy="12"
            r="10"
            stroke="currentColor"
            strokeWidth="4"
          />
          <path
            className="opacity-75"
            fill="currentColor"
            d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
          />
        </svg>
      )}
      {content}
    </button>
  );
};

export default Button;
