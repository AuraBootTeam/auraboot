import { type ActionFunctionArgs, type LoaderFunctionArgs, redirect } from 'react-router';
import {
  commitImpersonationSession,
  getTokenFromRequest,
  type ImpersonationSessionInfo,
} from '~/shared/services/session';

interface BackendResponse {
  code?: string;
  message?: string;
  data?: ImpersonationSessionInfo & { jwt?: string };
}

export async function loader(_args: LoaderFunctionArgs) {
  return redirect('/');
}

export async function action({ request }: ActionFunctionArgs) {
  const token = await getTokenFromRequest(request);
  if (!token)
    return Response.json({ ok: false, error: 'Authentication required' }, { status: 401 });

  const formData = await request.formData();
  const targetMemberPid = formData.get('targetMemberPid');
  const authorizationMethod = formData.get('authorizationMethod');
  const reason = formData.get('reason');
  const reference = formData.get('reference');
  if (
    typeof targetMemberPid !== 'string' ||
    !targetMemberPid.trim() ||
    typeof authorizationMethod !== 'string' ||
    !authorizationMethod.trim() ||
    typeof reason !== 'string' ||
    !reason.trim()
  ) {
    return Response.json(
      { ok: false, error: 'Authorization method and reason are required' },
      { status: 400 },
    );
  }

  const apiUrl = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
  let response: Response;
  try {
    response = await fetch(`${apiUrl}/api/impersonation-sessions`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'User-Agent': request.headers.get('User-Agent') || 'web-admin-bff',
      },
      body: JSON.stringify({
        targetMemberPid: targetMemberPid.trim(),
        authorizationMethod: authorizationMethod.trim(),
        reason: reason.trim(),
        reference: typeof reference === 'string' && reference.trim() ? reference.trim() : null,
        clientType: 'web',
      }),
    });
  } catch {
    return Response.json(
      { ok: false, error: 'Customer access service is temporarily unavailable' },
      { status: 503 },
    );
  }
  const result = (await response.json().catch(() => ({}))) as BackendResponse;
  if (!response.ok || result.code !== '0' || !result.data?.jwt) {
    return Response.json(
      { ok: false, error: result.message || 'Unable to start customer access' },
      { status: response.status >= 400 ? response.status : 400 },
    );
  }

  const { jwt, ...impersonation } = result.data;
  const setCookie = await commitImpersonationSession(request, jwt, impersonation);
  return Response.json({ ok: true, impersonation }, { headers: { 'Set-Cookie': setCookie } });
}
