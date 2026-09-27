import type { NextFunction, Request, Response } from 'express';
import { hasSameOrigin } from '~/server/middlewares/CsrfProtection';

interface LegacySessionMigrationDependencies {
  backendUrl: string;
  commitSession: (
    req: Request,
    res: Response,
    token: string,
    remember: boolean,
  ) => Promise<void>;
  fetchImpl?: typeof fetch;
}

export async function handleLegacySessionMigration(
  req: Request,
  res: Response,
  next: NextFunction,
  dependencies: LegacySessionMigrationDependencies,
): Promise<Response | void> {
  if (!hasSameOrigin(req)) {
    return res.status(403).json({ migrated: false });
  }
  const authorization = req.get('authorization') || '';
  const match = authorization.match(/^Bearer\s+(.+)$/i);
  if (!match?.[1]) {
    return res.status(401).json({ migrated: false });
  }

  try {
    const token = match[1];
    const validation = await (dependencies.fetchImpl ?? fetch)(
      `${dependencies.backendUrl}/api/auth/me`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (!validation.ok) {
      return res.status(401).json({ migrated: false });
    }
    await dependencies.commitSession(
      req,
      res,
      token,
      req.get('x-aura-remember') === 'true',
    );
    res.setHeader('Cache-Control', 'no-store');
    return res.status(204).end();
  } catch (error) {
    next(error);
  }
}
