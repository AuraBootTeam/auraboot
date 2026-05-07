import { describe, expect, it } from 'vitest';
import { echoRoundTrip, mockSkillClient } from '../skillClient.mock';
import type { SkillRequest } from '../../types/skill';

const baseRequest: SkillRequest = {
  skillName: 'echo',
  params: { text: 'world' },
  context: {
    route: '/dashboard',
    modelCode: null,
    pageId: null,
    selectedElement: null,
    recentOperations: [],
    lastCreatedResources: [],
  },
  idempotencyKey: 'fe_test_1',
  previewToken: null,
};

describe('mockSkillClient', () => {
  it('list() returns the echo meta', async () => {
    const skills = await mockSkillClient.list();
    expect(skills.map((s) => s.name)).toContain('echo');
  });

  it('execute({skillName: echo, text}) round-trips', async () => {
    const result = await mockSkillClient.execute(baseRequest);
    expect(result.status).toBe('SUCCESS');
    expect(result.skillName).toBe('echo');
    expect(result.payload.text).toBe('world');
    expect(result.traceId).toMatch(/^mock_trace_/);
  });

  it('unknown skill returns SKILL_NOT_FOUND error', async () => {
    const result = await mockSkillClient.execute({
      ...baseRequest,
      skillName: 'doesNotExist',
    });
    expect(result.status).toBe('ERROR');
    expect(result.errors[0].code).toBe('SKILL_NOT_FOUND');
  });

  it('echoRoundTrip helper returns input verbatim', async () => {
    expect(await echoRoundTrip('hello')).toBe('hello');
    expect(await echoRoundTrip('')).toBe('');
  });
});
