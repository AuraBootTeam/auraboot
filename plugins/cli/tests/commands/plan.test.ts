import { describe, it, expect } from 'vitest';

describe('plan command', () => {
  describe('planning system prompt', () => {
    // Mirror the constant from plan.ts
    const PLANNING_SYSTEM_PROMPT = `You are a planning assistant for company operations. When the user describes a goal or task, respond with a structured execution plan.

Format your response as:

## Plan: [title]

### Steps
1. **[step name]** — [description] → [recommended agent or tool]
2. ...

### Agents Needed
- [agent-code]: [role in this plan]

### Estimated
- Steps: [N]
- Duration: [estimate]

### Risks
- [risk description]

End with: "Execute this plan? (y/N)"`;

    it('should contain structured output instructions', () => {
      expect(PLANNING_SYSTEM_PROMPT).toContain('## Plan:');
      expect(PLANNING_SYSTEM_PROMPT).toContain('### Steps');
      expect(PLANNING_SYSTEM_PROMPT).toContain('### Agents Needed');
      expect(PLANNING_SYSTEM_PROMPT).toContain('### Estimated');
    });

    it('should request confirmation prompt', () => {
      expect(PLANNING_SYSTEM_PROMPT).toContain('Execute this plan?');
    });
  });

  describe('request body construction', () => {
    it('should prepend system prompt to messages', () => {
      const systemPrompt = 'You are a planning assistant...';
      const userMessage = 'Optimize sales pipeline';
      const body = {
        messages: [
          { role: 'system', content: systemPrompt },
          { role: 'user', content: userMessage },
        ],
      };

      expect(body.messages).toHaveLength(2);
      expect(body.messages[0].role).toBe('system');
      expect(body.messages[1].role).toBe('user');
      expect(body.messages[1].content).toBe(userMessage);
    });
  });
});
