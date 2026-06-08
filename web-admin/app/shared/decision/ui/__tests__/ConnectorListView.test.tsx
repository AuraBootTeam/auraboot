import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ConnectorListView, type Connector } from '../ConnectorListView';

const connectors: Connector[] = [
  { code: 'crm_webhook', name: 'CRM Webhook', type: 'WEBHOOK', endpoint: 'https://crm/hook', authMode: 'HMAC', health: 'HEALTHY', enabled: true },
  { code: 'sms_gw', name: 'SMS 网关', type: 'REST', endpoint: 'https://sms/api', authMode: 'APIKEY', health: 'DEGRADED', enabled: true },
  { code: 'legacy_mq', name: '旧 MQ', type: 'MQ', health: 'DOWN', enabled: false },
];

describe('ConnectorListView', () => {
  it('renders connectors + count + unhealthy badge (only enabled unhealthy)', () => {
    render(<ConnectorListView connectors={connectors} />);
    expect(screen.getByTestId('cl-count')).toHaveTextContent('3');
    // sms_gw is enabled+DEGRADED -> counts; legacy_mq is DOWN but disabled -> not counted
    expect(screen.getByTestId('cl-unhealthy')).toHaveTextContent('1');
    expect(screen.getByTestId('cl-row-sms_gw')).toHaveAttribute('data-health', 'DEGRADED');
  });

  it('filters by health', () => {
    render(<ConnectorListView connectors={connectors} />);
    fireEvent.change(screen.getByLabelText('health-filter'), { target: { value: 'HEALTHY' } });
    expect(screen.getByTestId('cl-count')).toHaveTextContent('1');
    expect(screen.getByTestId('cl-row-crm_webhook')).toBeInTheDocument();
    expect(screen.queryByTestId('cl-row-sms_gw')).not.toBeInTheDocument();
  });

  it('searches by code / name', () => {
    render(<ConnectorListView connectors={connectors} />);
    fireEvent.change(screen.getByLabelText('connector-search'), { target: { value: '网关' } });
    expect(screen.getByTestId('cl-count')).toHaveTextContent('1');
    expect(screen.getByTestId('cl-row-sms_gw')).toBeInTheDocument();
  });

  it('shows empty state', () => {
    render(<ConnectorListView connectors={connectors} />);
    fireEvent.change(screen.getByLabelText('connector-search'), { target: { value: 'nope' } });
    expect(screen.getByTestId('cl-empty')).toBeInTheDocument();
  });
});
