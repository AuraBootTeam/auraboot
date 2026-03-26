import { useI18n } from '~/contexts/I18nContext';

/**
 * Returns direction state derived from the current locale.
 *
 * Usage:
 *   const { isRTL, dir, flipSide } = useDirection();
 *   <div dir={dir} className={flipSide('pl-4', 'pr-4')}>...</div>
 */
export function useDirection() {
  const { isRTL } = useI18n();
  return {
    isRTL,
    dir: (isRTL ? 'rtl' : 'ltr') as 'rtl' | 'ltr',
    /**
     * Returns ltrClass when LTR, rtlClass when RTL.
     * Useful for flipping padding/margin/border classes:
     *   flipSide('pl-4', 'pr-4') → 'pl-4' in LTR, 'pr-4' in RTL
     */
    flipSide: (ltrClass: string, rtlClass: string): string => (isRTL ? rtlClass : ltrClass),
  };
}
