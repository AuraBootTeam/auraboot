import { type ActionFunctionArgs, type LoaderFunctionArgs, redirect } from 'react-router';
import {
  getImpersonationFromRequest,
  getTokenFromRequest,
  restoreOperatorSession,
} from '~/shared/services/session';

export async function loader(_args: LoaderFunctionArgs) {
  return redirect('/');
}

export async function action({ request }: ActionFunctionArgs) {
  const [token, impersonation] = await Promise.all([
    getTokenFromRequest(request),
    getImpersonationFromRequest(request),
  ]);
  if (!token || !impersonation) return redirect('/');

  const apiUrl = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
  let response: Response;
  try {
    response = await fetch(`${apiUrl}/api/impersonation-sessions/current/end`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'User-Agent': request.headers.get('User-Agent') || 'web-admin-bff',
      },
    });
  } catch {
    return Response.json(
      { ok: false, error: 'Customer access service is temporarily unavailable' },
      { status: 503 },
    );
  }
  if (!response.ok && response.status !== 401) {
    const result = (await response.json().catch(() => ({}))) as { message?: string };
    return Response.json(
      { ok: false, error: result.message || 'Unable to end customer access' },
      { status: response.status },
    );
  }

  const setCookie = await restoreOperatorSession(request);
  return redirect('/', { headers: { 'Set-Cookie': setCookie } });
}
