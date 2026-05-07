declare module '@testing-library/dom' {
  export const screen: any;
  export const waitFor: any;
  export const fireEvent: any;
}

declare module '@testing-library/react' {
  export const render: any;
  export const renderHook: any;
  export const act: any;
  export const screen: any;
  export const waitFor: any;
  export const fireEvent: any;
  export function cleanup(): void;
}

declare module '@modelcontextprotocol/sdk/client/index.js' {
  export class Client {
    constructor(info: { name: string; version: string }, options?: { capabilities?: Record<string, unknown> });
    connect(transport: unknown): Promise<void>;
    close(): Promise<void>;
    listTools(): Promise<{ tools: Array<{ name: string; description?: string; inputSchema?: unknown }> }>;
    callTool(params: { name: string; arguments: unknown }): Promise<{
      content: Array<{ type: string; text?: string }>;
      isError?: boolean;
      [key: string]: any;
    }>;
    [key: string]: any;
  }
}

declare module '@modelcontextprotocol/sdk/client/stdio.js' {
  export class StdioClientTransport {
    constructor(options: { command: string; args?: string[]; env?: Record<string, string> });
    [key: string]: any;
  }
}
