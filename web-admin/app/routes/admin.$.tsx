import { redirect, type LoaderFunctionArgs } from 'react-router';

export async function loader({ params, request }: LoaderFunctionArgs) {
  const url = new URL(request.url);
  const targetPath = params['*'] ? `/${params['*']}` : '/home';
  return redirect(`${targetPath}${url.search}`, 302);
}

export default function AdminNamespaceRedirect() {
  return null;
}
