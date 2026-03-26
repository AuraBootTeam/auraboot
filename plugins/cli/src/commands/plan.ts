import chalk from 'chalk';
import { ApiClient, EXIT } from '../client/api-client.js';
import { streamSse } from '../client/sse-client.js';

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

interface PlanOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

export async function planCommand(message: string, options: PlanOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const token = client.getToken()!;
  const baseUrl = client.getBaseUrl();

  const isAgentMode = options.agentMode || process.env.AURA_AGENT_MODE === '1';

  if (!isAgentMode) {
    console.log();
    process.stdout.write(chalk.dim('Planning...'));
  }

  let started = false;
  let fullContent = '';

  await streamSse({
    url: `${baseUrl}/api/ai/aurabot/chat/stream`,
    token,
    body: {
      messages: [
        { role: 'system', content: PLANNING_SYSTEM_PROMPT },
        { role: 'user', content: message },
      ],
    },
    onContent: (text) => {
      if (!started) {
        if (!isAgentMode) {
          process.stdout.write('\r\x1b[K');
        }
        started = true;
      }
      fullContent += text;
      if (isAgentMode) return;
      process.stdout.write(text);
    },
    onDone: () => {
      if (isAgentMode) {
        console.log(JSON.stringify({ content: fullContent }));
      } else {
        console.log();
        console.log();
      }
    },
    onError: (error) => {
      if (!isAgentMode) {
        process.stdout.write('\r\x1b[K');
      }
      console.error(chalk.red(`Error: ${error}`));
      process.exit(EXIT.FAILURE);
    },
  });
}
