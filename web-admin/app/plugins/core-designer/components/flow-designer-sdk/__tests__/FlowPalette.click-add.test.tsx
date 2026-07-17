import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (key: unknown) => (typeof key === 'string' ? key : ''),
}));

import { FlowPalette } from '../core/FlowPalette';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { useFlowStore } from '../store/useFlowStore';

describe('FlowPalette click-to-add interaction', () => {
  beforeEach(() => {
    useFlowStore.getState().reset();
    nodeRegistry.clear();
  });

  it('adds and selects a node when a palette item is clicked', () => {
    nodeRegistry.register({
      type: 'action-send-webhook',
      label: '发送 Webhook',
      icon: 'Send',
      category: 'action',
      description: '向外部系统发送 Webhook 请求',
      defaultConfig: {
        actionType: 'send_webhook',
        eventType: 'automation.record.updated',
      },
    });

    render(<FlowPalette categoryOrder={['action']} />);

    fireEvent.click(screen.getByTestId('palette-node-action-send-webhook'));

    const state = useFlowStore.getState();
    expect(state.nodes).toHaveLength(1);
    expect(state.selectedNodeId).toBe(state.nodes[0].id);
    expect(state.isDirty).toBe(true);
    expect(state.nodes[0]).toMatchObject({
      type: 'action-send-webhook',
      position: { x: 120, y: 120 },
      data: {
        label: '发送 Webhook',
        config: {
          actionType: 'send_webhook',
          eventType: 'automation.record.updated',
        },
      },
    });
  });

  it('places click-added nodes in the next visible row instead of overlapping or pushing offscreen', () => {
    nodeRegistry.register({
      type: 'action-send-webhook',
      label: '发送 Webhook',
      icon: 'Send',
      category: 'action',
      defaultConfig: { actionType: 'send_webhook' },
    });
    useFlowStore.getState().addNode({
      type: 'trigger-record-create',
      position: { x: 120, y: 120 },
      data: { label: '记录创建', config: {} },
    });

    render(<FlowPalette categoryOrder={['action']} />);

    fireEvent.click(screen.getByTestId('palette-node-action-send-webhook'));

    const added = useFlowStore
      .getState()
      .nodes.find((node) => node.type === 'action-send-webhook');
    expect(added?.position.x).toBe(120);
    expect(added?.position.y).toBe(240);
  });

  it('shows provider availability from node metadata without disabling the palette item', () => {
    nodeRegistry.register({
      type: 'action-send-sms',
      label: '发送短信',
      icon: 'MessageSquareText',
      category: 'action',
      description: '向手机号发送短信',
      defaultConfig: { actionType: 'send_sms' },
      metadata: {
        availability: {
          unavailable: true,
          reason: '当前环境未配置真实短信 provider',
          source: 'decision-action-catalog',
          actionType: 'SEND_SMS',
        },
      },
    });

    render(<FlowPalette categoryOrder={['action']} />);

    const item = screen.getByTestId('palette-node-action-send-sms');
    expect(item).toHaveTextContent('发送短信');
    expect(screen.getByTestId('palette-node-action-send-sms-status')).toHaveTextContent(
      '不可用',
    );
    expect(screen.getByTestId('palette-node-action-send-sms-status-text')).toHaveTextContent(
      '当前环境未配置真实短信 provider',
    );

    fireEvent.click(item);

    expect(useFlowStore.getState().nodes).toHaveLength(1);
    expect(useFlowStore.getState().nodes[0].data.config).toMatchObject({
      actionType: 'send_sms',
    });
  });
});
