import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { SourceTypeBadge } from '~/shared/components/SourceTypeBadge';

// @testing-library/react v14+ performs cleanup automatically via afterEach
describe('SourceTypeBadge', () => {

  it.each([
    ['physical', '物理表'],
    ['namedQuery', 'NamedQuery'],
    ['endpoint', 'Endpoint'],
    ['sqlView', 'SQL View'],
  ])('renders %s as "%s"', (type, expected) => {
    render(<SourceTypeBadge sourceType={type} />);
    expect(screen.getByText(expected)).toBeInTheDocument();
  });

  it('defaults to physical when sourceType is undefined', () => {
    render(<SourceTypeBadge />);
    expect(screen.getByText('物理表')).toBeInTheDocument();
  });

  it('shows raw value for unknown sourceType', () => {
    render(<SourceTypeBadge sourceType="projection" />);
    expect(screen.getByText('projection')).toBeInTheDocument();
  });
});
