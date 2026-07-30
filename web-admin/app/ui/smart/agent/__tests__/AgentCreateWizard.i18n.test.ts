import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('AgentCreateWizard i18n catalog', () => {
  it('covers every wizard and template key in both supported base locales', () => {
    const source = readFileSync('app/ui/smart/agent/AgentCreateWizard.tsx', 'utf8');
    const catalog = JSON.parse(
      readFileSync('../plugins/core-aurabot/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;
    const entries = new Map(catalog.map((entry) => [entry.key, entry]));
    const keys = new Set(
      [...source.matchAll(/'(ai\.(?:wizard|template)\.[^']+)'/g)].map((match) => match[1]),
    );

    expect(keys.size).toBeGreaterThan(40);
    for (const key of keys) {
      expect(entries.get(key), `missing catalog entry for ${key}`).toBeDefined();
      expect(entries.get(key)?.['zh-CN'], `missing zh-CN translation for ${key}`).toBeTruthy();
      expect(entries.get(key)?.['en-US'], `missing en-US translation for ${key}`).toBeTruthy();
    }
  });

  it('localizes deployment channel and initiator labels without exposing enum codes', () => {
    const catalog = JSON.parse(
      readFileSync('../plugins/core-aurabot/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;
    const entries = new Map(catalog.map((entry) => [entry.key, entry]));
    const keys = [
      'ai.colleagues.policy.channel.web',
      'ai.colleagues.policy.channel.imGroup',
      'ai.colleagues.policy.channel.schedule',
      'ai.colleagues.policy.channel.event',
      'ai.colleagues.policy.channel.webhook',
      'ai.colleagues.policy.channel.api',
      'ai.colleagues.policy.initiator.human',
      'ai.colleagues.policy.initiator.system',
      'ai.colleagues.policy.initiator.schedule',
      'ai.colleagues.policy.initiator.event',
      'ai.colleagues.policy.initiator.agentHandoff',
    ];

    for (const key of keys) {
      expect(entries.get(key)?.['zh-CN'], `missing zh-CN translation for ${key}`).toBeTruthy();
      expect(entries.get(key)?.['en-US'], `missing en-US translation for ${key}`).toBeTruthy();
    }
    expect(entries.get('ai.colleagues.policy.channel.imGroup')?.['zh-CN']).not.toContain(
      'im_group',
    );
    expect(entries.get('ai.colleagues.policy.initiator.agentHandoff')?.['zh-CN']).not.toContain(
      'agent_handoff',
    );
  });
});
