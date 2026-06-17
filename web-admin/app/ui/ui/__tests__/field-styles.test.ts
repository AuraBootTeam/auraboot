/**
 * field-styles is the single source of control chrome for app/ui/ui + the smart
 * form controls that import it. This test locks the UX Design System §2 contract
 * on the exported class strings (heights / radius / focus ring / disabled /
 * semantic colors). Dark-mode classes are out of scope until T3.
 */

import { describe, it, expect } from 'vitest';
import {
  fieldControlBase,
  fieldFocusStyles,
  fieldErrorFocusStyles,
  fieldVariantStyles,
  fieldInputHeightStyles,
  fieldSizeStyles,
} from '~/ui/ui/field-styles';

describe('field-styles — UX Design System §2 control contract', () => {
  it('control base uses the semantic control radius, not rounded-md', () => {
    expect(fieldControlBase).toContain('rounded-control');
    expect(fieldControlBase).not.toContain('rounded-md');
  });

  it('focus uses the unified focus ring (shadow-focus), not ad-hoc blue ring', () => {
    expect(fieldFocusStyles).toContain('focus-visible:shadow-focus');
    expect(fieldFocusStyles).toContain('focus-visible:outline-none');
    expect(fieldFocusStyles).not.toContain('ring-blue-500');
    expect(fieldErrorFocusStyles).toContain('focus-visible:shadow-focus');
  });

  it('default variant uses semantic surface/border/text tokens', () => {
    expect(fieldVariantStyles.default).toContain('border-border-strong');
    expect(fieldVariantStyles.default).toContain('bg-panel');
    expect(fieldVariantStyles.default).toContain('text-text');
    expect(fieldVariantStyles.default).not.toContain('border-gray-300');
  });

  it('error variant uses semantic status-red border', () => {
    expect(fieldVariantStyles.error).toContain('border-status-red');
    expect(fieldVariantStyles.error).not.toContain('border-red-300');
  });

  it('field heights reference control tokens (sm 28 / field 34 default / lg 40)', () => {
    expect(fieldInputHeightStyles.small).toContain('var(--ds-control-sm)');
    expect(fieldInputHeightStyles.medium).toContain('var(--ds-control-field)');
    expect(fieldInputHeightStyles.large).toContain('var(--ds-control-lg)');
  });

  it('field text size uses the body type-scale token', () => {
    expect(fieldSizeStyles.medium).toContain('text-body');
  });
});
