import { describe, expect, it } from 'vitest';

import type { DecisionAction } from '~/shared/decision/api/decisionApi';
import { resolveDecisionActionAvailability } from '../actionAvailability';

describe('resolveDecisionActionAvailability', () => {
  it('uses consumer-scoped provider dependencies for any unavailable action type', () => {
    const action: DecisionAction = {
      actionType: 'WEBHOOK',
      label: '发送 Webhook',
      handlerAvailable: true,
      availabilityStatus: 'AVAILABLE',
      consumerAvailability: [
        {
          consumerType: 'EVENT_POLICY',
          handlerAvailable: false,
          availabilityStatus: 'UNAVAILABLE',
          availabilityReason: 'Webhook 投递子系统不可用',
          providerDependencies: [
            {
              providerType: 'WEBHOOK',
              providerCodes: ['platform_webhook_dispatcher'],
              label: 'Webhook 投递子系统',
              required: true,
              available: false,
              availabilityStatus: 'UNAVAILABLE',
              availabilityReason: 'Webhook 投递子系统不可用',
            },
          ],
        },
      ],
    };

    expect(resolveDecisionActionAvailability(action, 'EVENT_POLICY')).toEqual({
      unavailable: true,
      reason: 'Webhook 投递子系统不可用',
      providerSummary: '依赖：Webhook 投递子系统 (platform_webhook_dispatcher) · 不可用',
    });
  });

  it('keeps available provider dependencies out of warning surfaces', () => {
    const action: DecisionAction = {
      actionType: 'SEND_IM',
      label: '发送 IM',
      handlerAvailable: true,
      availabilityStatus: 'AVAILABLE',
      providerDependencies: [
        {
          providerType: 'IM',
          providerCodes: ['system_bot_message'],
          label: '平台 IM / bot message',
          required: true,
          available: true,
          availabilityStatus: 'AVAILABLE',
        },
      ],
    };

    expect(resolveDecisionActionAvailability(action, 'AUTOMATION')).toEqual({
      unavailable: false,
      reason: '',
      providerSummary: '',
    });
  });

  it('falls back to provider dependency reason when availability reason is missing', () => {
    const action: DecisionAction = {
      actionType: 'WEBHOOK',
      label: '发送 Webhook',
      handlerAvailable: false,
      availabilityStatus: 'UNAVAILABLE',
      providerDependencies: [
        {
          providerType: 'WEBHOOK',
          providerCodes: ['platform_webhook_dispatcher'],
          label: 'Webhook 投递子系统',
          required: true,
          available: false,
          availabilityStatus: 'UNAVAILABLE',
          availabilityReason: 'dispatcher connection refused',
        },
      ],
    };

    expect(resolveDecisionActionAvailability(action, 'EVENT_POLICY')).toEqual({
      unavailable: true,
      reason: 'Webhook 投递子系统不可用: dispatcher connection refused',
      providerSummary: '依赖：Webhook 投递子系统 (platform_webhook_dispatcher) · 不可用',
    });
  });
});

describe('localized availability presentation', () => {
  it.each(['en-US', 'en-GB'])(
    'localizes shared warning labels for %s while preserving provider data',
    (locale) => {
      const action: DecisionAction = {
        actionType: 'WEBHOOK',
        handlerAvailable: false,
        providerDependencies: [
          {
            providerType: 'WEBHOOK',
            label: 'User provider $&',
            required: true,
            available: false,
            availabilityReason: 'User-authored failure $&',
            providerCodes: ['provider_contract'],
          },
        ],
      };
      expect(resolveDecisionActionAvailability(action, 'EVENT_POLICY', locale)).toEqual({
        unavailable: true,
        reason: 'User provider $& unavailable: User-authored failure $&',
        providerSummary: 'Dependency: User provider $& (provider_contract) · Unavailable',
      });
      expect(action.providerDependencies![0].availabilityReason).toBe('User-authored failure $&');
      expect(
        resolveDecisionActionAvailability(
          { actionType: 'NOTIFY', handlerAvailable: false },
          'EVENT_POLICY',
          locale,
        ).reason,
      ).toBe('Action handler is currently unavailable');
    },
  );
});
