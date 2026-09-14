import { describe, expect, it, vi } from "vitest";
import { OpenPlatformClient, OpenPlatformError } from "../src/index";

function json(status: number, body: unknown, headers?: HeadersInit): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

describe("OpenPlatformClient", () => {
  it("exchanges and caches a scoped token while preserving public resource aliases", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        json(200, { access_token: "token-1", expires_in: 300 }),
      )
      .mockResolvedValueOnce(
        json(
          200,
          { pid: "asset-1", assetCode: "A-1", name: "Laptop" },
          { ETag: '"asset-v1"' },
        ),
      )
      .mockResolvedValueOnce(
        json(
          200,
          { pid: "asset-2", assetCode: "A-2", name: "Phone" },
          { ETag: '"asset-v1"' },
        ),
      );
    const client = new OpenPlatformClient({
      baseUrl: "https://tenant.example/",
      clientId: "client",
      clientSecret: "secret",
      scope: "assets.read",
      fetch: fetcher,
    });

    await expect(client.getAsset("asset-1")).resolves.toMatchObject({
      assetCode: "A-1",
    });
    await expect(client.getAsset("asset-2")).resolves.toMatchObject({
      assetCode: "A-2",
    });

    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(String(fetcher.mock.calls[0][1]?.body)).toContain(
      "scope=assets.read",
    );
    expect(fetcher.mock.calls[1][1]?.headers).toMatchObject({
      Authorization: "Bearer token-1",
    });
  });

  it("refreshes once on 401 and reuses the same idempotency key", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        json(200, { access_token: "expired", expires_in: 300 }),
      )
      .mockResolvedValueOnce(
        json(401, { code: "invalid_token", message: "expired" }),
      )
      .mockResolvedValueOnce(
        json(200, { access_token: "fresh", expires_in: 300 }),
      )
      .mockResolvedValueOnce(
        json(200, {
          command: "assets.assign",
          idempotentReplay: false,
          resource: { pid: "asset-1" },
        }),
      );
    const client = new OpenPlatformClient({
      baseUrl: "https://tenant.example",
      clientId: "client",
      clientSecret: "secret",
      fetch: fetcher,
    });

    await client.assignAsset(
      "asset-1",
      "alice",
      "assign-asset-0001",
      '"asset-v1"',
    );

    expect(fetcher).toHaveBeenCalledTimes(4);
    expect(fetcher.mock.calls[1][1]?.headers).toMatchObject({
      "Idempotency-Key": "assign-asset-0001",
      "If-Match": '"asset-v1"',
    });
    expect(fetcher.mock.calls[3][1]?.headers).toMatchObject({
      Authorization: "Bearer fresh",
      "Idempotency-Key": "assign-asset-0001",
      "If-Match": '"asset-v1"',
    });
  });

  it("supports baseUrl plus accessToken, opaque pages, and no 412 retry", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        json(200, {
          items: [{ pid: "receipt-1" }],
          hasMore: true,
          nextCursor: "opaque",
        }),
      )
      .mockResolvedValueOnce(
        json(412, { code: "precondition_failed", message: "stale" }),
      );
    const client = new OpenPlatformClient({
      baseUrl: "https://tenant.example",
      accessToken: "provided-token",
      fetch: fetcher,
    });

    await expect(client.listStockIns(25)).resolves.toMatchObject({
      nextCursor: "opaque",
    });
    await expect(
      client.confirmStockIn("receipt-1", "confirm-0001", '"receipt-v1"'),
    ).rejects.toMatchObject({ status: 412, code: "precondition_failed" });
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[0][0]).toContain("limit=25");
    expect(fetcher.mock.calls[0][1]?.headers).toMatchObject({
      Authorization: "Bearer provided-token",
    });
  });

  it("surfaces stable error metadata and request correlation", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        json(200, { access_token: "token", expires_in: 300 }),
      )
      .mockResolvedValueOnce(
        json(
          429,
          { code: "rate_limit_exceeded", message: "slow down" },
          { "X-Request-Id": "req-429" },
        ),
      );
    const client = new OpenPlatformClient({
      baseUrl: "https://tenant.example",
      clientId: "client",
      clientSecret: "secret",
      fetch: fetcher,
    });

    const error = await client.getAsset("asset-1").catch((caught) => caught);
    expect(error).toBeInstanceOf(OpenPlatformError);
    expect(error).toMatchObject({
      status: 429,
      code: "rate_limit_exceeded",
      requestId: "req-429",
    });
  });
});
