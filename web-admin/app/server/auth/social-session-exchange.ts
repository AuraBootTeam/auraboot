import type { NextFunction, Request, Response } from 'express';

interface SocialSessionExchangeDependencies {
  backendUrl: string;
  commitSession: (req: Request, res: Response, token: string, remember: boolean) => Promise<void>;
  fetchImpl?: typeof fetch;
}

export async function handleSocialSessionExchange(
  req: Request,
  res: Response,
  next: NextFunction,
  dependencies: SocialSessionExchangeDependencies,
): Promise<Response | void> {
  try {
    const body = req.body && Object.keys(req.body).length > 0 ? JSON.stringify(req.body) : undefined;
    const upstream = await (dependencies.fetchImpl ?? fetch)(
      `${dependencies.backendUrl}${req.originalUrl}`,
      {
        method: 'POST',
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body,
      },
    );
    const payload = await upstream.json().catch(() => null) as Record<string, any> | null;
    if (!payload) {
      return res.status(502).json({ code: 'social_session_exchange_invalid_response' });
    }

    const token = payload.data?.jwt;
    if (typeof token === 'string' && token) {
      if (upstream.ok) {
        await dependencies.commitSession(req, res, token, true);
      }
      payload.data = {
        ...payload.data,
        jwt: null,
        ...(upstream.ok ? { sessionEstablished: true } : {}),
      };
    }

    res.setHeader('Cache-Control', 'no-store');
    return res.status(upstream.status).json(payload);
  } catch (error) {
    next(error);
  }
}
