export interface OpenPlatformClientOptions {
  baseUrl: string;
  clientId: string;
  clientSecret: string;
  scope?: string;
  fetch?: typeof globalThis.fetch;
}

export interface AssetResource {
  pid: string;
  assetCode?: string;
  name?: string;
  serialNumber?: string;
  status?: string;
  assignedTo?: string;
  location?: string;
  updatedAt?: string;
}

export class OpenPlatformError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly requestId?: string,
  ) {
    super(message);
    this.name = "OpenPlatformError";
  }
}

export class OpenPlatformClient {
  private readonly baseUrl: string;
  private readonly fetcher: typeof globalThis.fetch;
  private token?: { value: string; expiresAt: number };

  constructor(private readonly options: OpenPlatformClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, "");
    this.fetcher = options.fetch ?? globalThis.fetch;
  }

  async getAsset(pid: string): Promise<AssetResource> {
    return this.request(
      `/api/open/v1/resources/assets/${encodeURIComponent(pid)}`,
    );
  }

  async assignAsset(
    targetPid: string,
    assignee: string,
    idempotencyKey: string,
  ): Promise<{
    command: "assets.assign";
    idempotentReplay: boolean;
    resource: AssetResource;
  }> {
    return this.request("/api/open/v1/commands/assets.assign:execute", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify({ targetPid, input: { assignee } }),
    });
  }

  private async accessToken(forceRefresh = false): Promise<string> {
    if (
      !forceRefresh &&
      this.token &&
      this.token.expiresAt > Date.now() + 30_000
    )
      return this.token.value;
    const form = new URLSearchParams({
      grant_type: "client_credentials",
      client_id: this.options.clientId,
      client_secret: this.options.clientSecret,
    });
    if (this.options.scope) form.set("scope", this.options.scope);
    const response = await this.fetcher(`${this.baseUrl}/oauth2/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form,
    });
    const body = (await response.json()) as {
      access_token?: string;
      expires_in?: number;
      code?: string;
      message?: string;
    };
    if (!response.ok || !body.access_token) throw this.error(response, body);
    this.token = {
      value: body.access_token,
      expiresAt: Date.now() + (body.expires_in ?? 300) * 1000,
    };
    return this.token.value;
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const token = await this.accessToken(attempt === 1);
      const response = await this.fetcher(`${this.baseUrl}${path}`, {
        ...init,
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
          ...init.headers,
        },
      });
      const body = (await response.json().catch(() => ({}))) as T & {
        code?: string;
        message?: string;
      };
      if (response.ok) return body;
      if (response.status !== 401 || attempt === 1)
        throw this.error(response, body);
      this.token = undefined;
    }
    throw new Error("Unreachable Open Platform request state");
  }

  private error(
    response: Response,
    body: { code?: string; message?: string },
  ): OpenPlatformError {
    return new OpenPlatformError(
      response.status,
      body.code ?? "http_error",
      body.message ?? `HTTP ${response.status}`,
      response.headers.get("X-Request-Id") ?? undefined,
    );
  }
}
