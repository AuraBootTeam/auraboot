import { Outlet, useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { MarketingHeader } from '../components/MarketingHeader';
import { MarketingFooter } from '../components/MarketingFooter';
import { getTokenFromRequest } from '~/services/session';
import { getUserInfo } from '~/services/userService';
import '../styles/site.css';

export async function loader({ request }: LoaderFunctionArgs) {
  const token = await getTokenFromRequest(request);
  let isLoggedIn = false;
  if (token) {
    try {
      const { user } = await getUserInfo(request);
      isLoggedIn = !!user;
    } catch {
      /* anonymous visitor */
    }
  }
  return { isLoggedIn };
}

export default function MarketingLayout() {
  const { isLoggedIn } = useLoaderData<typeof loader>();
  return (
    <div className="min-h-screen flex flex-col">
      <MarketingHeader isLoggedIn={isLoggedIn} />
      <main className="flex-1">
        <Outlet />
      </main>
      <MarketingFooter />
    </div>
  );
}
