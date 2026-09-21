import type { ActionFunctionArgs, LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/shared/services/session';

const API_ROOT = () => process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';

async function backend(token: string, path: string, method: 'GET' | 'POST' = 'GET') {
  return fetch(`${API_ROOT()}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}` },
  });
}

async function readBackend(response: Response) {
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    const message = body?.message || body?.desc || `教师码服务请求失败（${response.status}）`;
    return {
      ok: false as const,
      response: Response.json({ success: false, error: message }, { status: response.status }),
    };
  }
  return { ok: true as const, body };
}

async function current(token: string) {
  const parsed = await readBackend(await backend(token, '/api/tenant/invite-code/current'));
  if (!parsed.ok) return parsed.response;
  return Response.json({ success: true, data: parsed.body?.data ?? null });
}

export async function loader({ request }: LoaderFunctionArgs) {
  const token = await getTokenFromRequest(request);
  if (!token) return Response.json({ success: false, error: '请重新登录' }, { status: 401 });
  return current(token);
}

export async function action({ request }: ActionFunctionArgs) {
  const token = await getTokenFromRequest(request);
  if (!token) return Response.json({ success: false, error: '请重新登录' }, { status: 401 });

  const payload = (await request.json().catch(() => ({}))) as { action?: unknown };
  if (payload.action !== 'generate' && payload.action !== 'reset') {
    return Response.json({ success: false, error: '不支持的教师码操作' }, { status: 400 });
  }

  let oldCode = '';
  if (payload.action === 'reset') {
    const parsedCurrent = await readBackend(
      await backend(token, '/api/tenant/invite-code/current'),
    );
    if (!parsedCurrent.ok) return parsedCurrent.response;
    oldCode = String(parsedCurrent.body?.data?.code || '');
  }

  // Product contract: this BFF endpoint only issues the school teacher role.
  // The browser cannot choose or append arbitrary role codes.
  const generated = await readBackend(
    await backend(
      token,
      '/api/tenant/invite-code/generate?expiryDays=30&roleCodes=xy_teacher',
      'POST',
    ),
  );
  if (!generated.ok) return generated.response;
  const newCode = String(generated.body?.data || '');

  if (oldCode && newCode && oldCode !== newCode) {
    const revoked = await readBackend(
      await backend(
        token,
        `/api/tenant/invite-code/revoke?code=${encodeURIComponent(oldCode)}`,
        'POST',
      ),
    );
    if (!revoked.ok) return revoked.response;
  }

  return current(token);
}
