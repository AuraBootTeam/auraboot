import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import IcpComplianceFooter from '../IcpComplianceFooter';

const rootLoaderData = vi.hoisted(() => ({
  value: {
    icpCompliance: {
      enabled: false,
      siteTitle: '个人技术',
      recordNumber: '浙ICP备2023054087号',
      siteDisplayName: 'AuraBoot',
    },
  },
}));

vi.mock('~/root', () => ({
  useRootLoaderData: () => rootLoaderData.value,
}));

describe('IcpComplianceFooter', () => {
  beforeEach(() => {
    rootLoaderData.value.icpCompliance = {
      enabled: false,
      siteTitle: '个人技术',
      recordNumber: '浙ICP备2023054087号',
      siteDisplayName: 'AuraBoot',
    };
  });

  it('does not render when the compliance profile is disabled', () => {
    render(<IcpComplianceFooter />);

    expect(screen.queryByTestId('icp-compliance-footer')).not.toBeInTheDocument();
  });

  it('renders the configured filing link when enabled', () => {
    rootLoaderData.value.icpCompliance = {
      enabled: true,
      siteTitle: '个人技术',
      recordNumber: '浙ICP备2023054087号',
      siteDisplayName: 'AuraBoot 个人技术',
    };

    render(<IcpComplianceFooter />);

    expect(screen.getByTestId('icp-record-link')).toHaveTextContent('浙ICP备2023054087号');
    expect(screen.getByTestId('icp-record-link')).toHaveAttribute(
      'href',
      'https://beian.miit.gov.cn/',
    );
  });
});
