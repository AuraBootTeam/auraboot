import { redirect } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';

export function loader({ params }: LoaderFunctionArgs) {
  const tableName = params.tableName || '';
  return redirect(`/p/${tableName}/new`, 301);
}
