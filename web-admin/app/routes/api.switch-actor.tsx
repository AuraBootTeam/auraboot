import { type ActionFunctionArgs, type LoaderFunctionArgs, redirect } from 'react-router';
import { createUserSession, getTokenFromRequest } from '~/shared/services/session';
import { safeRedirect } from '~/utils/utils';

export async function loader(_args: LoaderFunctionArgs) {
  return redirect('/');
}

export async function action({ request }: ActionFunctionArgs) {
  const token = await getTokenFromRequest(request);
  if (!token) return redirect('/login');

  const formData = await request.formData();
  const partyId = formData.get('partyId');
  const redirectTo = safeRedirect(formData.get('redirectTo'), '/');
  if (typeof partyId !== 'string' || !/^\d+$/.test(partyId)) {
    return redirect(redirectTo);
  }

  const apiUrl = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
  const response = await fetch(`${apiUrl}/api/actors/switch`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'User-Agent': request.headers.get('User-Agent') || 'web-admin-bff',
    },
    body: JSON.stringify({ partyId }),
  });
  if (!response.ok) return redirect(redirectTo);

  const result = await response.json();
  const replacementToken = result?.data?.jwt;
  if (!replacementToken) return redirect(redirectTo);
  return createUserSession({
    request,
    token: replacementToken,
    remember: false,
    redirectTo,
  });
}
