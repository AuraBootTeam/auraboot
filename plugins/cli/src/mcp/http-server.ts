import http from 'http';
import chalk from 'chalk';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { ApiClient } from '../client/api-client.js';
import { makeAuditWrapper } from './audit.js';
import { authenticateHttpRequest } from './http-auth.js';
import { DEFAULT_MCP_PROFILE, resolveMcpProfile } from './profiles.js';
import { buildToolRegistry } from './server.js';

/**
 * Streamable HTTP MCP server — lets a remote agent (Codex / Claude / Cursor)
 * connect over HTTP instead of a local stdio child process.
 *
 * Auth model (owner decision, 2026-07-23): static token + tenant header. Each
 * request carries `Authorization: Bearer <aura-token>`; the tenant is resolved
 * per-request from that JWT (the multi-client analogue of the stdio server's
 * startup tenant pin). Tools are scoped by profile and every call is audited.
 *
 * Runs stateless (`sessionIdGenerator: undefined`): a fresh MCP server +
 * profile-filtered registry is built per request against that request's token,
 * so different callers never share tenant state.
 */

const DEFAULT_HTTP_PORT = 7878;

async function readJsonBody(req: http.IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  if (chunks.length === 0) return undefined;
  const raw = Buffer.concat(chunks).toString('utf8').trim();
  return raw ? JSON.parse(raw) : undefined;
}

export async function startHttpMcpServer(opts: {
  port?: number;
  host?: string;
  env?: string;
  profile?: string;
}): Promise<http.Server> {
  const profile = resolveMcpProfile(opts.profile ?? process.env.AURA_MCP_PROFILE);
  const port = opts.port ?? Number(process.env.AURA_MCP_HTTP_PORT ?? DEFAULT_HTTP_PORT);
  const host = opts.host ?? '127.0.0.1';

  const server = http.createServer(async (req, res) => {
    try {
      const auth = authenticateHttpRequest(req.headers.authorization);
      if (!auth.ok) {
        res.writeHead(auth.status, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ jsonrpc: '2.0', error: { code: -32001, message: auth.reason } }));
        return;
      }

      const body = req.method === 'POST' ? await readJsonBody(req) : undefined;

      // Per-request client scoped to THIS caller's token + tenant.
      const client = new ApiClient({ token: auth.token, env: opts.env, interactive: false });
      const audit = makeAuditWrapper(auth.ctx, { remoteClient: client });

      const mcpServer = new McpServer({ name: 'aura', version: '2.0.0' });
      buildToolRegistry(client, { profile }).attachTo(mcpServer, audit);

      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
      res.on('close', () => {
        transport.close();
        mcpServer.close();
      });

      await mcpServer.connect(transport);
      await transport.handleRequest(req, res, body);
    } catch (e) {
      if (!res.headersSent) {
        res.writeHead(500, { 'content-type': 'application/json' });
        res.end(
          JSON.stringify({ jsonrpc: '2.0', error: { code: -32603, message: (e as Error).message } }),
        );
      }
    }
  });

  await new Promise<void>((resolve) => server.listen(port, host, resolve));

  const profileHint = profile === DEFAULT_MCP_PROFILE ? ' (default)' : '';
  console.error(
    chalk.dim(`[aura-mcp-http] listening on http://${host}:${port} — profile=${profile}${profileHint}`),
  );
  console.error(
    chalk.dim('[aura-mcp-http] auth: Authorization: Bearer <aura-token> (tenant resolved per-request)'),
  );
  return server;
}
