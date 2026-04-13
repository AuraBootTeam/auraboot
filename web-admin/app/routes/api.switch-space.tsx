import { type ActionFunctionArgs, type LoaderFunctionArgs, redirect } from 'react-router';
import { createUserSession, getTokenFromRequest } from '~/shared/services/session';

/**
 * Resource route for switching spaces (tenants).
 * POST: calls backend tenant-selection API, updates session cookie, redirects.
 * GET: redirects to home (this route has no UI).
 */
export async function loader(_args: LoaderFunctionArgs) {
  return redirect('/');
}

export async function action({ request }: ActionFunctionArgs) {
  const token = await getTokenFromRequest(request);
  if (!token) {
    return redirect('/login');
  }

  const formData = await request.formData();
  const tenantId = formData.get('tenantId') as string;
  const redirectTo = (formData.get('redirectTo') as string) || '/';

  if (!tenantId) {
    // No specific tenant — redirect to tenant-selection to pick one
    return redirect(redirectTo || '/tenant-selection');
  }

  const apiUrl = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
  const response = await fetch(`${apiUrl}/api/tenant-selection/process`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    // tenantId is a string from form data. Validate it's numeric before embedding
    // in JSON template literal (avoids injection + preserves large ID precision).
    body: /^\d+$/.test(tenantId)
      ? `{"action":"select","tenantId":${tenantId}}`
      : JSON.stringify({ action: 'select', tenantId }),
  });

  if (!response.ok) {
    return redirect('/');
  }

  const result = await response.json();
  const data = result.data;

  if (data?.status === 'success' && data?.jwt) {
    return createUserSession({
      request,
      token: data.jwt,
      remember: false,
      redirectTo,
    });
  }

  return redirect('/');
}
