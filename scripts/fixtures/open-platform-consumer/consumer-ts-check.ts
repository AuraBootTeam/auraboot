import { OpenPlatformClient, OpenPlatformError, type ResourcePage } from "@auraboot/open-platform-sdk";

const client: OpenPlatformClient = new OpenPlatformClient({
  baseUrl: "https://tenant.example.com",
  clientId: "ab_client_demo",
  clientSecret: "secret-demo",
  scope: "assets.read",
});
const page: ResourcePage<{ pid: string }> = await client.listResources("assets", 10);
const err = new OpenPlatformError(403, "insufficient_scope", "denied", "req_1");
const status: number = err.status;
const code: string = err.code;
const requestId: string | undefined = err.requestId;
console.log(page.items.length, status, code, requestId ?? "none");
