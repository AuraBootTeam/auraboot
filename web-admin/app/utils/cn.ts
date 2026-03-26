import clsx, { type ClassValue } from 'clsx';

/**
 * Utility for conditionally joining class names.
 * Thin wrapper around clsx for Tailwind CSS usage.
 */
export function cn(...inputs: ClassValue[]) {
  return clsx(inputs);
}
