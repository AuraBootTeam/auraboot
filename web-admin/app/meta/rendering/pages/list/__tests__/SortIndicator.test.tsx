import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { SortIndicator } from '../SortIndicator';

describe('SortIndicator', () => {
  it('renders without direction (default state — both arrows gray)', () => {
    const { container } = render(<SortIndicator />);
    const paths = container.querySelectorAll('path');
    expect(paths).toHaveLength(2);
    // Both arrows should be gray (#c0c4cc) when no direction
    expect(paths[0].getAttribute('fill')).toBe('#c0c4cc');
    expect(paths[1].getAttribute('fill')).toBe('#c0c4cc');
  });

  it('renders with direction="asc" (top arrow blue #2563eb)', () => {
    const { container } = render(<SortIndicator direction="asc" />);
    const paths = container.querySelectorAll('path');
    // Top arrow (ascending) should be blue
    expect(paths[0].getAttribute('fill')).toBe('#2563eb');
    // Bottom arrow should be gray
    expect(paths[1].getAttribute('fill')).toBe('#c0c4cc');
  });

  it('renders with direction="desc" (bottom arrow blue #2563eb)', () => {
    const { container } = render(<SortIndicator direction="desc" />);
    const paths = container.querySelectorAll('path');
    // Top arrow should be gray
    expect(paths[0].getAttribute('fill')).toBe('#c0c4cc');
    // Bottom arrow (descending) should be blue
    expect(paths[1].getAttribute('fill')).toBe('#2563eb');
  });

  it('renders priority badge when priority > 0', () => {
    const { getByText } = render(<SortIndicator direction="asc" priority={2} />);
    const badge = getByText('2');
    expect(badge).toBeInTheDocument();
    expect(badge).toHaveClass('rounded-full', 'bg-blue-600');
  });

  it('does not render priority badge when priority is undefined', () => {
    const { container } = render(<SortIndicator direction="asc" />);
    // Should only have the svg span, no badge span
    const badges = container.querySelectorAll('.rounded-full');
    expect(badges).toHaveLength(0);
  });

  it('does not render priority badge when priority is 0', () => {
    const { container } = render(<SortIndicator direction="asc" priority={0} />);
    const badges = container.querySelectorAll('.rounded-full');
    expect(badges).toHaveLength(0);
  });
});
