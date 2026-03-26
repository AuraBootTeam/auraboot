import chalk from 'chalk';
import { ApiClient, EXIT } from '../client/api-client.js';
import { streamSse } from '../client/sse-client.js';

interface AskOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

export async function askCommand(message: string, options: AskOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const token = client.getToken()!;
  const baseUrl = client.getBaseUrl();

  const isAgentMode = options.agentMode || process.env.AURA_AGENT_MODE === '1';

  if (!isAgentMode) {
    console.log();
    process.stdout.write(chalk.dim('Thinking...'));
  }

  let started = false;
  let fullContent = '';

  await streamSse({
    url: `${baseUrl}/api/ai/aurabot/chat/stream`,
    token,
    body: {
      messages: [{ role: 'user', content: message }],
    },
    onContent: (text) => {
      if (!started) {
        if (!isAgentMode) {
          // Clear "Thinking..." line
          process.stdout.write('\r\x1b[K');
        }
        started = true;
      }
      fullContent += text;
      if (isAgentMode) return; // buffer for JSON output
      process.stdout.write(text);
    },
    onDone: () => {
      if (isAgentMode) {
        console.log(JSON.stringify({ content: fullContent }));
      } else {
        console.log(); // newline after streamed content
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
