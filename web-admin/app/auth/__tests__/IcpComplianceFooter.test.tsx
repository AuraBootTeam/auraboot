import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import IcpComplianceFooter from '../IcpComplianceFooter';

describe('IcpComplianceFooter', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('does not render when the compliance profile is disabled', () => {
    vi.stubEnv('VITE_ICP_COMPLIANCE_ENABLED', 'false');

    render(<IcpComplianceFooter />);

    expect(screen.queryByTestId('icp-compliance-footer')).not.toBeInTheDocument();
  });

  it('renders the configured filing link when enabled', () => {
    vi.stubEnv('VITE_ICP_COMPLIANCE_ENABLED', 'true');

    render(<IcpComplianceFooter />);

    expect(screen.getByTestId('icp-record-link')).toHaveTextContent('浙ICP备2023054087号');
    expect(screen.getByTestId('icp-record-link')).toHaveAttribute(
      'href',
      'https://beian.miit.gov.cn/',
    );
  });
});
