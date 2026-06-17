/**
 * Base control components apply the unified UX Design System §2 chrome:
 * semantic colors (accent / panel / border tokens), rounded-control, the
 * shadow-focus ring, and disabled:opacity-50 — no ad-hoc blue rings or gray
 * palette borders. Dark-mode classes are out of scope until T3.
 */

import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { buttonVariants } from '~/ui/ui/button';
import { Input } from '~/ui/ui/input';
import { Textarea } from '~/ui/ui/textarea';
import { Checkbox } from '~/ui/ui/checkbox';
import { Switch } from '~/ui/ui/switch';

describe('Button variants', () => {
  it('default uses accent surface + unified ring/radius/disabled', () => {
    const cls = buttonVariants({ variant: 'default' });
    expect(cls).toContain('bg-accent');
    expect(cls).toContain('rounded-control');
    expect(cls).toContain('focus-visible:shadow-focus');
    expect(cls).toContain('disabled:opacity-50');
    expect(cls).not.toContain('ring-blue-500');
    expect(cls).not.toContain('rounded-md');
  });

  it('sizes reference control-height tokens', () => {
    expect(buttonVariants({ size: 'sm' })).toContain('var(--ds-control-sm)');
    expect(buttonVariants({ size: 'default' })).toContain('var(--ds-control-md)');
    expect(buttonVariants({ size: 'lg' })).toContain('var(--ds-control-lg)');
  });

  it('destructive uses semantic status-red, link uses accent', () => {
    expect(buttonVariants({ variant: 'destructive' })).toContain('bg-status-red');
    expect(buttonVariants({ variant: 'link' })).toContain('text-accent');
  });
});

describe('Input / Textarea', () => {
  it('Input applies control radius + shadow-focus, drops blue ring & gray border', () => {
    const { container } = render(<Input />);
    const cls = container.querySelector('input')!.className;
    expect(cls).toContain('rounded-control');
    expect(cls).toContain('focus-visible:shadow-focus');
    expect(cls).not.toContain('ring-blue-500');
    expect(cls).not.toContain('border-gray-300');
  });

  it('Textarea applies control radius + shadow-focus', () => {
    const { container } = render(<Textarea />);
    const cls = container.querySelector('textarea')!.className;
    expect(cls).toContain('rounded-control');
    expect(cls).toContain('focus-visible:shadow-focus');
    expect(cls).not.toContain('ring-blue-500');
  });
});

describe('Checkbox / Switch', () => {
  it('Checkbox uses accent checked state + shadow-focus', () => {
    const { container } = render(<Checkbox />);
    const cls = container.querySelector('button')!.className;
    expect(cls).toContain('data-[state=checked]:bg-accent');
    expect(cls).toContain('focus-visible:shadow-focus');
    expect(cls).not.toContain('ring-blue-500');
  });

  it('Switch uses accent checked state + shadow-focus', () => {
    const { container } = render(<Switch />);
    const cls = container.querySelector('button')!.className;
    expect(cls).toContain('data-[state=checked]:bg-accent');
    expect(cls).toContain('focus-visible:shadow-focus');
    expect(cls).not.toContain('ring-blue-500');
  });
});
