import type express from 'express';

import { JWT_TOKEN_KEY, REFRESH_TOKEN_KEY, TOKEN_EXPIRY_KEY } from '~/constants/AuthConstant';
import { sessionStorage } from '~/shared/services/session';

export function handleLogoutPost(backendUrl: string) {
  return async (req: express.Request, res: express.Response, next: express.NextFunction) => {
    try {
      const session = await sessionStorage.getSession(req.headers.cookie);
      const token = session.get(JWT_TOKEN_KEY);

      if (token) {
        try {
          await fetch(`${backendUrl}/api/user/sessions/current`, {
            method: 'DELETE',
            headers: {
              Authorization: `Bearer ${token}`,
            },
          });
        } catch (error) {
          console.warn('Failed to revoke backend session during BFF logout', error);
        }
      }

      session.unset(JWT_TOKEN_KEY);
      session.unset(REFRESH_TOKEN_KEY);
      session.unset(TOKEN_EXPIRY_KEY);

      res.setHeader('Set-Cookie', await sessionStorage.destroySession(session));
      return res.redirect(302, '/login');
    } catch (error) {
      return next(error);
    }
  };
}
