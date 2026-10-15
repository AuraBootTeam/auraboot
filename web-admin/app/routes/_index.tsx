import { redirect, type LoaderFunctionArgs } from 'react-router';
import { getUserInfo } from '~/shared/services/userService';

const DEFAULT_AUTH_HOME = '/home';

export async function loader({ request, context }: LoaderFunctionArgs) {
  const { user } = await getUserInfo(request, context);

  if (!user) {
    return redirect('/login');
  }

  return redirect(DEFAULT_AUTH_HOME);
}

export default function IndexRedirectRoute() {
  return null;
}
