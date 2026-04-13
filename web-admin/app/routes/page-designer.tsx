/**
 * Page Designer - List Route (Redirect)
 *
 * The page schema list is now DSL-driven at /p/page_schema.
 * This route redirects for backwards compatibility.
 * The editor route (/page-designer/:id) is unchanged.
 *
 * @since 8.0.0
 */

import { redirect } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';

export const loader = async (_args: LoaderFunctionArgs) => {
  return redirect('/p/page_schema');
};

export default function PageDesignerListRedirect() {
  return null;
}
