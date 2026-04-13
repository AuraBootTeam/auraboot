import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getItemList, submitSearchQuery } from '~/shared/services/form';
import FormList from '~/routes/FormList';

// 使用FormList的loader逻辑，但指定store-list schema
export const loader = async ({ request }: LoaderFunctionArgs) => {
  const url = new URL(request.url);
  const searchParams = url.searchParams;

  // 使用store-list作为schema id
  const schemaId = 'store-list';

  // 如果有查询参数，使用submitSearchQuery处理
  if (searchParams.toString()) {
    // 将URL参数转换为FormData对象
    const formData = new FormData();
    for (const [key, value] of searchParams.entries()) {
      formData.append(key, value);
    }

    const result = await submitSearchQuery(formData, request, schemaId);

    return {
      schema: result,
    };
  }

  // 没有查询参数时，使用原来的getItemList
  const result = await getItemList(request, schemaId);

  return {
    schema: result,
  };
};

// 直接使用FormList组件
export default FormList;
