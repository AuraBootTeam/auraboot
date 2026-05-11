export interface CorsOriginInput {
  origin?: string;
  allowedOrigins: string[];
  allowedDevPorts: Set<string>;
  credentials: boolean;
  environment: string;
}

export interface CorsOriginResult {
  allowOrigin?: string;
  allowCredentials: boolean;
}

function getExplicitAllowedOrigin(origin: string, allowedOrigins: string[]): string | undefined {
  return allowedOrigins.find((allowed) => allowed !== '*' && allowed === origin);
}

export function resolveCorsOrigin(input: CorsOriginInput): CorsOriginResult {
  const { origin, allowedOrigins, allowedDevPorts, credentials, environment } = input;

  if (!origin) {
    return { allowCredentials: false };
  }

  const explicitAllowedOrigin = getExplicitAllowedOrigin(origin, allowedOrigins);
  if (explicitAllowedOrigin) {
    return { allowOrigin: explicitAllowedOrigin, allowCredentials: credentials };
  }

  if (environment !== 'development') {
    return { allowCredentials: false };
  }

  try {
    const url = new URL(origin);
    const isLocalDevHost = url.hostname === 'localhost' || url.hostname === '127.0.0.1';
    if (isLocalDevHost && allowedDevPorts.has(url.port)) {
      return { allowOrigin: origin, allowCredentials: false };
    }
  } catch {
    return { allowCredentials: false };
  }

  return { allowCredentials: false };
}
