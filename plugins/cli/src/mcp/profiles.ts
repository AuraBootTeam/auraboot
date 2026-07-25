/**
 * MCP tool profiles — scope which AuraBoot tools an agent session may see.
 *
 * Mirrors NocoBase's `x-mcp-packages`: expose a minimal default tool set and
 * require high-risk capabilities to be enabled explicitly. Every tool has a
 * tier; a profile grants a set of tiers.
 *
 *   read          → read/discovery tools only (default, safest)
 *   dsl-authoring → read + create_* authoring tools
 *   full          → everything, including plugin import / rollback
 *
 * An unknown tool name is treated as the most restrictive tier (`admin`) so a
 * newly added tool can never silently leak into a narrow profile.
 */

export type McpProfileName = 'read' | 'dsl-authoring' | 'full';
export type McpToolTier = 'read' | 'author' | 'admin';

export const DEFAULT_MCP_PROFILE: McpProfileName = 'read';

export const MCP_PROFILE_NAMES: readonly McpProfileName[] = ['read', 'dsl-authoring', 'full'];

const PROFILE_RANK: Record<McpProfileName, number> = {
  read: 0,
  'dsl-authoring': 1,
  full: 2,
};

export class McpRequestProfileError extends Error {
  constructor(
    message: string,
    readonly status: 400 | 403,
  ) {
    super(message);
    this.name = 'McpRequestProfileError';
  }
}

/** Tiers granted by each profile (cumulative). */
const PROFILE_TIERS: Record<McpProfileName, readonly McpToolTier[]> = {
  read: ['read'],
  'dsl-authoring': ['read', 'author'],
  full: ['read', 'author', 'admin'],
};

/**
 * Source-of-truth tool → tier map. Keep in sync with buildToolRegistry; the
 * registry drift-guard test asserts every registered tool has an entry here.
 */
export const TOOL_TIERS: Record<string, McpToolTier> = {
  // read / discovery
  query_entity: 'read',
  run_named_query: 'read',
  list_agents: 'read',
  list_tools: 'read',
  dispatch_agent: 'read',
  ask_aurabot: 'read',
  query_dsl_capabilities: 'read',
  query_existing_models: 'read',
  query_page_schemas: 'read',
  describe_command_pipeline: 'read',
  // authoring (create_*)
  create_model: 'author',
  create_page_schema: 'author',
  create_command: 'author',
  // admin (destructive / irreversible-ish)
  import_plugin: 'admin',
  rollback_import: 'admin',
};

/** Unknown tools fall back to the most restrictive tier. */
function tierOf(toolName: string): McpToolTier {
  return TOOL_TIERS[toolName] ?? 'admin';
}

export function resolveMcpProfile(name: string | undefined): McpProfileName {
  if (name === undefined || name === '') return DEFAULT_MCP_PROFILE;
  if ((MCP_PROFILE_NAMES as readonly string[]).includes(name)) {
    return name as McpProfileName;
  }
  throw new Error(
    `Unknown MCP profile "${name}". Valid profiles: ${MCP_PROFILE_NAMES.join(', ')}.`,
  );
}

/**
 * Resolve the optional Streamable HTTP `x-aura-tools` request header.
 *
 * The server startup profile is a capability ceiling. A caller may narrow its
 * own request (for example full -> read), but a header can never widen what the
 * operator allowed at startup.
 */
export function resolveRequestMcpProfile(
  header: string | string[] | undefined,
  serverProfile: McpProfileName,
): McpProfileName {
  if (header === undefined) return serverProfile;
  if (Array.isArray(header)) {
    throw new McpRequestProfileError(
      'x-aura-tools must contain exactly one profile name.',
      400,
    );
  }

  let requested: McpProfileName;
  try {
    requested = resolveMcpProfile(header.trim());
  } catch (error) {
    throw new McpRequestProfileError((error as Error).message, 400);
  }

  if (PROFILE_RANK[requested] > PROFILE_RANK[serverProfile]) {
    throw new McpRequestProfileError(
      `Requested MCP profile "${requested}" exceeds the server ceiling "${serverProfile}".`,
      403,
    );
  }
  return requested;
}

export function toolAllowedInProfile(toolName: string, profile: McpProfileName): boolean {
  return PROFILE_TIERS[profile].includes(tierOf(toolName));
}

export function filterToolsByProfile<T extends { name: string }>(
  tools: readonly T[],
  profile: McpProfileName,
): T[] {
  return tools.filter((t) => toolAllowedInProfile(t.name, profile));
}
