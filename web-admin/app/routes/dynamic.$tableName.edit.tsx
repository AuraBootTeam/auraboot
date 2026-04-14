import { redirect } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';

export function loader({ params }: LoaderFunctionArgs) {
  const tableName = params.tableName || '';
  const recordId = params.recordId || '';
  return redirect(`/p/${tableName}/edit/${recordId}`, 301);
}
