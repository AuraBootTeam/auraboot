export interface OpenPlatformClientOptions {
  baseUrl: string;
  clientId?: string;
  clientSecret?: string;
  accessToken?: string;
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

export interface StockInResource {
  pid: string;
  receiptCode?: string;
  productPid?: string;
  warehousePid?: string;
  quantity?: string | number;
  unitCost?: string | number;
  status?: string;
  supplier?: string;
  receiptDate?: string;
  updatedAt?: string;
}

export interface VersionedResource<T> {
  resource: T;
  etag: string;
}
export interface ResourcePage<T> {
  items: T[];
  hasMore: boolean;
  nextCursor: string | null;
}

export type PublicFilter =
  | { field: string; operator: "IS_NULL" }
  | { field: string; operator: "EQ" | "NE" | "GT" | "GE" | "LT" | "LE" | "LIKE"; value: string | number | boolean }
  | { field: string; operator: "IN"; value: readonly (string | number | boolean)[] };

export interface ResourceListOptions {
  limit?: number;
  cursor?: string;
  filters?: readonly PublicFilter[];
  sort?: string;
  direction?: "ASC" | "DESC";
}

export interface WriteMetadata {
  idempotentReplay: boolean;
  requestId?: string;
}

export interface ResourceMutation<T> extends VersionedResource<T>, WriteMetadata {
  location?: string;
}

export interface QueryRequest<P = Record<string, unknown>> {
  parameters?: P;
  limit?: number;
  cursor?: string;
}

export interface QueryPage<T> extends ResourcePage<T> {
  total: number;
  schemaVersion: number;
}

/** Parsed, authenticated Webhook body; verify the raw bytes before parsing. */
export interface ModelEvent<T> {
  id: string;
  type: `open.resource.${string}.${"created" | "updated" | "deleted"}`;
  schemaVersion: number;
  occurredAt: string;
  subject: { type: string; pid: string };
  recordVersion: number;
  correlationId: string | null;
  data: T;
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
    const credentialPair = Boolean(options.clientId && options.clientSecret);
    if (Boolean(options.accessToken) === credentialPair) {
      throw new Error("Configure either accessToken or clientId/clientSecret");
    }
  }

  async getAsset(pid: string): Promise<AssetResource> {
    return (await this.getAssetVersioned(pid)).resource;
  }

  async getAssetVersioned(
    pid: string,
  ): Promise<VersionedResource<AssetResource>> {
    return this.getResource("assets", pid);
  }

  async listAssets(
    limit = 50,
    cursor?: string,
  ): Promise<ResourcePage<AssetResource>> {
    return this.listResources("assets", limit, cursor);
  }

  async getStockIn(pid: string): Promise<VersionedResource<StockInResource>> {
    return this.getResource("inventory.stock-ins", pid);
  }

  async listStockIns(
    limit = 50,
    cursor?: string,
  ): Promise<ResourcePage<StockInResource>> {
    return this.listResources("inventory.stock-ins", limit, cursor);
  }

  async assignAsset(
    targetPid: string,
    assignee: string,
    idempotencyKey: string,
    etag: string,
  ): Promise<{
    command: "assets.assign";
    idempotentReplay: boolean;
    resource: AssetResource;
    etag: string;
  }> {
    return this.request("/api/open/v1/commands/assets.assign:execute", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey, "If-Match": etag },
      body: JSON.stringify({ targetPid, input: { assignee } }),
    });
  }

  async confirmStockIn(
    targetPid: string,
    idempotencyKey: string,
    etag: string,
  ): Promise<{
    command: "inventory.stock-ins.confirm";
    idempotentReplay: boolean;
    resource: StockInResource;
    etag: string;
  }> {
    return this.request(
      "/api/open/v1/commands/inventory.stock-ins.confirm:execute",
      {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey, "If-Match": etag },
        body: JSON.stringify({ targetPid, input: {} }),
      },
    );
  }

  async listResources<T>(
    resourceCode: string,
    options: number | ResourceListOptions = 50,
    cursor?: string,
  ): Promise<ResourcePage<T>> {
    const settings = typeof options === "number" ? { limit: options, cursor } : options;
    const query = new URLSearchParams({ limit: String(settings.limit ?? 50) });
    if (settings.cursor) query.set("cursor", settings.cursor);
    if (settings.filters) query.set("filters", JSON.stringify(settings.filters));
    if (settings.sort) query.set("sort", settings.sort);
    if (settings.direction) query.set("direction", settings.direction);
    return this.request(
      `/api/open/v1/resources/${encodeURIComponent(resourceCode)}?${query}`,
    );
  }

  async createResource<T>(
    resourceCode: string,
    input: object,
    idempotencyKey: string,
  ): Promise<ResourceMutation<T>> {
    return this.mutateResource(resourceCode, undefined, "POST", input, idempotencyKey);
  }

  async updateResource<T>(
    resourceCode: string,
    pid: string,
    input: object,
    idempotencyKey: string,
    etag: string,
  ): Promise<ResourceMutation<T>> {
    return this.mutateResource(resourceCode, pid, "PATCH", input, idempotencyKey, etag);
  }

  async deleteResource(
    resourceCode: string,
    pid: string,
    idempotencyKey: string,
    etag: string,
  ): Promise<WriteMetadata> {
    const { response } = await this.requestWithResponse<void>(
      `/api/open/v1/resources/${encodeURIComponent(resourceCode)}/${encodeURIComponent(pid)}`,
      { method: "DELETE", headers: { "Idempotency-Key": idempotencyKey, "If-Match": etag } },
    );
    return this.writeMetadata(response);
  }

  async executeQuery<T, P = Record<string, unknown>>(
    queryCode: string,
    request: QueryRequest<P> = {},
  ): Promise<QueryPage<T>> {
    return this.request(`/api/open/v1/queries/${encodeURIComponent(queryCode)}:execute`, {
      method: "POST",
      body: JSON.stringify(request),
    });
  }

  private async mutateResource<T>(
    resourceCode: string,
    pid: string | undefined,
    method: "POST" | "PATCH",
    input: object,
    idempotencyKey: string,
    etag?: string,
  ): Promise<ResourceMutation<T>> {
    const path = `/api/open/v1/resources/${encodeURIComponent(resourceCode)}`
      + (pid === undefined ? "" : `/${encodeURIComponent(pid)}`);
    const { body, response } = await this.requestWithResponse<T>(path, {
      method,
      headers: { "Idempotency-Key": idempotencyKey, ...(etag === undefined ? {} : { "If-Match": etag }) },
      body: JSON.stringify(input),
    });
    return {
      resource: body,
      etag: this.requireEtag(response),
      ...this.writeMetadata(response),
      location: response.headers.get("Location") ?? undefined,
    };
  }

  private writeMetadata(response: Response): WriteMetadata {
    return {
      idempotentReplay: response.headers.get("Idempotent-Replay") === "true",
      requestId: response.headers.get("X-Request-Id") ?? undefined,
    };
  }

  private requireEtag(response: Response): string {
    const etag = response.headers.get("ETag");
    if (!etag) throw new OpenPlatformError(response.status, "missing_etag", "Resource response omitted ETag",
      response.headers.get("X-Request-Id") ?? undefined);
    return etag;
  }

  async getResource<T>(
    resourceCode: string,
    pid: string,
  ): Promise<VersionedResource<T>> {
    const { body, response } = await this.requestWithResponse<T>(
      `/api/open/v1/resources/${encodeURIComponent(resourceCode)}/${encodeURIComponent(pid)}`,
    );
    return { resource: body, etag: this.requireEtag(response) };
  }

  private async accessToken(forceRefresh = false): Promise<string> {
    if (this.options.accessToken) return this.options.accessToken;
    if (
      !forceRefresh &&
      this.token &&
      this.token.expiresAt > Date.now() + 30_000
    )
      return this.token.value;
    const form = new URLSearchParams({
      grant_type: "client_credentials",
      client_id: this.options.clientId!,
      client_secret: this.options.clientSecret!,
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
    return (await this.requestWithResponse<T>(path, init)).body;
  }

  private async requestWithResponse<T>(
    path: string,
    init: RequestInit = {},
  ): Promise<{ body: T; response: Response }> {
    const attempts = this.options.accessToken ? 1 : 2;
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      const token = await this.accessToken(attempt === 1);
      const response = await this.fetcher(`${this.baseUrl}${path}`, {
        ...init,
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
          ...init.headers,
        },
      });
      let parsed: unknown;
      if (response.status !== 204) {
        try { parsed = await response.json(); }
        catch {
          if (response.ok) throw new OpenPlatformError(response.status, "invalid_response", "Expected a JSON response",
            response.headers.get("X-Request-Id") ?? undefined);
          parsed = {};
        }
      }
      const body = parsed as T & {
        code?: string;
        message?: string;
      };
      if (response.ok) return { body, response };
      if (response.status !== 401 || attempt === attempts - 1)
        throw this.error(response, body ?? {});
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
