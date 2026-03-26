import chalk from 'chalk';
import { ApiClient, EXIT } from '../../client/api-client.js';
import { streamSse } from '../../client/sse-client.js';
import { readStdin } from './stdin.js';

interface AnalyzeOptions {
  token?: string;
  env?: string;
  prompt?: string;
}

/**
 * aura analyze <analysis> — Server-side AI analytics via pipeline.
 *
 * Reads JSON from stdin, sends to AuraBot with analysis context,
 * outputs result to stdout (for further piping).
 *
 * Examples:
 *   aura query crm_lead | aura analyze churn-risk
 *   aura query crm_opportunity | aura analyze "which deals are at risk"
 *   cat data.json | aura analyze "summarize trends"
 */
export async function analyzeCommand(analysis: string, options: AnalyzeOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const token = client.getToken()!;
  const baseUrl = client.getBaseUrl();

  // Read piped data from stdin
  const inputData = await readStdin();

  // Build analysis prompt
  const dataContext = inputData
    ? `\n\nData to analyze (${inputData.length} records):\n${JSON.stringify(inputData, null, 2)}`
    : '';

  const userPrompt = options.prompt
    ? `${options.prompt}\n\nAnalysis type: ${analysis}${dataContext}`
    : `Analyze the following data. Analysis type: ${analysis}${dataContext}

Respond with a structured JSON result containing:
- "summary": brief text summary
- "insights": array of key findings
- "recommendations": array of suggested actions
- "data": processed/scored data if applicable`;

  let fullContent = '';

  await streamSse({
    url: `${baseUrl}/api/ai/aurabot/chat/stream`,
    token,
    body: {
      messages: [{ role: 'user', content: userPrompt }],
    },
    onContent: (text) => {
      fullContent += text;
    },
    onDone: () => {
      // Try to extract JSON from response, fallback to raw text
      const jsonMatch = fullContent.match(/```json\s*([\s\S]*?)```/);
      if (jsonMatch) {
        console.log(jsonMatch[1].trim());
      } else {
        // Try parsing entire response as JSON
        try {
          JSON.parse(fullContent);
          console.log(fullContent);
        } catch {
          // Wrap text response in JSON
          console.log(JSON.stringify({ analysis, result: fullContent }));
        }
      }
    },
    onError: (error) => {
      console.error(chalk.red(`Analysis failed: ${error}`));
      process.exit(EXIT.FAILURE);
    },
  });
}
