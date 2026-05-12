import { redirect, type LoaderFunctionArgs } from 'react-router';

export async function loader({ request }: LoaderFunctionArgs) {
  const url = new URL(request.url);
  return redirect(`/home${url.search}`, 302);
}

export default function AdminIndexRedirect() {
  return null;
}
