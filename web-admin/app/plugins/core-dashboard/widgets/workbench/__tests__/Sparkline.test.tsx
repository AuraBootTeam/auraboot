import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { Sparkline } from '../Sparkline';

describe('Sparkline', () => {
  it('renders a polyline with one point per data entry', () => {
    const { container } = render(
      <Sparkline points={[1, 2, 3, 4, 5, 6, 7]} width={60} height={20} stroke="#635bff" />,
    );
    const polyline = container.querySelector('polyline');
    expect(polyline).not.toBeNull();
    const pts = polyline!.getAttribute('points')!.trim().split(/\s+/);
    expect(pts).toHaveLength(7);
    expect(polyline!.getAttribute('stroke')).toBe('#635bff');
  });

  it('renders a flat baseline line when points is empty', () => {
    const { container } = render(<Sparkline points={[]} width={60} height={20} />);
    const line = container.querySelector('line');
    expect(line).not.toBeNull();
  });

  it('renders a flat baseline line for a single-value series', () => {
    const { container } = render(<Sparkline points={[5]} width={60} height={20} />);
    const line = container.querySelector('line');
    expect(line).not.toBeNull();
  });
});
