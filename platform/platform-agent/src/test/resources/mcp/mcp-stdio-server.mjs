import readline from 'node:readline';

const input = readline.createInterface({ input: process.stdin });

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

input.on('line', (line) => {
  const request = JSON.parse(line);
  if (request.method === 'initialize') {
    send({
      jsonrpc: '2.0',
      id: request.id,
      result: {
        protocolVersion: request.params.protocolVersion,
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: 'stdio-fixture', version: '1.0' },
      },
    });
    return;
  }
  if (request.method === 'notifications/initialized') return;
  if (request.method === 'tools/list') {
    send({
      jsonrpc: '2.0',
      id: request.id,
      result: {
        tools: [{
          name: 'echo',
          description: 'Echo args and environment',
          inputSchema: { type: 'object' },
        }],
      },
    });
    return;
  }
  if (request.method === 'tools/call') {
    send({
      jsonrpc: '2.0',
      id: request.id,
      result: {
        content: [{
          type: 'text',
          text: JSON.stringify({
            arguments: request.params.arguments,
            environment: process.env.MCP_FIXTURE_VALUE,
          }),
        }],
      },
    });
  }
});
