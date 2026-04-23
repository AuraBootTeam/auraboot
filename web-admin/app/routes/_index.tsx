import { redirect, type LoaderFunctionArgs } from 'react-router';
import { getUserInfo } from '~/shared/services/userService';

const DEFAULT_AUTH_HOME = '/home';

export async function loader({ request }: LoaderFunctionArgs) {
  const { user } = await getUserInfo(request);

  if (!user) {
    return redirect('/login');
  }

  return redirect(DEFAULT_AUTH_HOME);
}

export default function IndexRedirectRoute() {
  return null;
}
