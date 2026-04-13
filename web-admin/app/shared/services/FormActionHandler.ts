import { redirect } from 'react-router';
import type { ActionFunctionArgs } from 'react-router';
import { ApiAdapter, type ApiConfig, type ApiType } from '~/shared/services/ApiAdapter';

// 定义表单操作配置
export interface FormActionConfig {
  // 操作类型
  actionType: string;
  // API 类型
  apiType?: ApiType;
  // API 配置
  api: ApiConfig;
  // 成功后的重定向路径 (可以是字符串或函数)
  redirectPath?: string | ((result: any) => string);
  // 数据转换函数 (可选)
  transformData?: (formData: FormData) => any;
}

/**
 * 创建表单 Action 处理函数
 * @param configs 表单操作配置数组
 * @returns Action 函数
 */
export function createFormAction(configs: FormActionConfig[]) {
  return async ({ request }: ActionFunctionArgs) => {
    // 获取表单数据
    const formData = await request.formData();
    const actionType = formData.get('actionType') as string;

    // 查找匹配的配置
    const config = configs.find((c) => c.actionType === actionType);
    if (!config) {
      return {
        error: {
          code: 'invalid_action',
          desc: `未知的操作类型: ${actionType}`,
        },
      };
    }

    // 转换数据
    const data = config.transformData
      ? config.transformData(formData)
      : Object.fromEntries(formData);

    // 调用 API
    const result = await ApiAdapter.call(config.apiType ?? 'http', config.api, data, request);

    // 处理结果
    if (!result.success) {
      return { error: { code: result.code, desc: result.desc, data: result.error } };
    }

    // 处理重定向
    if (config.redirectPath) {
      const path =
        typeof config.redirectPath === 'function'
          ? config.redirectPath(result.data)
          : config.redirectPath;

      return redirect(path);
    }

    // 返回成功结果
    return { success: true, data: result.data };
  };
}
