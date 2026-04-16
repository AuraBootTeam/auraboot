/**
 * Import/Export Utilities
 *
 * 提供 Schema 导入导出相关的工具函数
 */

import type { CanvasSchema } from '~/plugins/core-designer/components/studio/workbench/canvas/types';
import { validateSchema, type ValidationResult } from '~/plugins/core-designer/components/studio/workbench/utils/validation';
import { createDefaultSchema } from '~/plugins/core-designer/components/studio/workbench/utils/schemaUtils';

/**
 * 导出格式类型
 */
export type ExportFormat = 'json' | 'yaml' | 'xml';

/**
 * 导出选项
 */
export interface ExportOptions {
  format: ExportFormat;
  minify?: boolean;
  includeMetadata?: boolean;
  includeComments?: boolean;
}

/**
 * 导入选项
 */
export interface ImportOptions {
  validate?: boolean;
  merge?: boolean;
  baseSchema?: CanvasSchema;
}

/**
 * 导入结果
 */
export interface ImportResult {
  success: boolean;
  schema?: CanvasSchema;
  validation?: ValidationResult;
  error?: string;
}

/**
 * 导出 Schema 为 JSON 字符串
 */
export function exportSchema(
  schema: CanvasSchema,
  options: ExportOptions = { format: 'json' },
): string {
  try {
    switch (options.format) {
      case 'json':
        return exportToJson(schema, options);
      case 'yaml':
        return exportToYaml(schema, options);
      case 'xml':
        return exportToXml(schema, options);
      default:
        throw new Error(`Unsupported export format: ${options.format}`);
    }
  } catch (error) {
    throw new Error(`Export failed: ${error instanceof Error ? error.message : 'Unknown error'}`);
  }
}

/**
 * 从字符串导入 Schema
 */
export function importSchema(data: string, options: ImportOptions = {}): ImportResult {
  try {
    let schema: CanvasSchema;

    // 尝试解析不同格式
    try {
      schema = JSON.parse(data);
    } catch {
      try {
        schema = parseYaml(data);
      } catch {
        try {
          schema = parseXml(data);
        } catch {
          return {
            success: false,
            error: 'Unable to parse data. Supported formats: JSON, YAML, XML',
          };
        }
      }
    }

    // 验证 Schema
    let validation: ValidationResult | undefined;
    if (options.validate !== false) {
      validation = validateSchema(schema);
      if (!validation.valid) {
        return {
          success: false,
          schema,
          validation,
          error: 'Schema validation failed',
        };
      }
    }

    // 合并 Schema
    if (options.merge && options.baseSchema) {
      schema = mergeSchemas(options.baseSchema, schema);
    }

    return {
      success: true,
      schema,
      validation,
    };
  } catch (error) {
    return {
      success: false,
      error: `Import failed: ${error instanceof Error ? error.message : 'Unknown error'}`,
    };
  }
}

/**
 * 导出为 JSON 格式
 */
function exportToJson(schema: CanvasSchema, options: ExportOptions): string {
  const exportData = prepareExportData(schema, options);

  if (options.minify) {
    return JSON.stringify(exportData);
  } else {
    return JSON.stringify(exportData, null, 2);
  }
}

/**
 * 导出为 YAML 格式
 */
function exportToYaml(schema: CanvasSchema, options: ExportOptions): string {
  const exportData = prepareExportData(schema, options);

  // 简单的 YAML 序列化实现
  // 在实际项目中，建议使用 js-yaml 库
  return convertToYaml(exportData, 0);
}

/**
 * 导出为 XML 格式
 */
function exportToXml(schema: CanvasSchema, options: ExportOptions): string {
  const exportData = prepareExportData(schema, options);

  let xml = '<?xml version="1.0" encoding="UTF-8"?>\n';
  xml += '<schema>\n';
  xml += convertToXml(exportData, 1);
  xml += '</schema>';

  return xml;
}

/**
 * 准备导出数据
 */
function prepareExportData(schema: CanvasSchema, options: ExportOptions): any {
  const data: any = JSON.parse(JSON.stringify(schema));

  if (!options.includeMetadata) {
    delete data.metadata;
  }

  if (options.includeComments) {
    // 添加注释信息
    (data as any)._comments = {
      version: 'Schema version',
      components: 'Form components configuration',
      layout: 'Layout configuration',
      theme: 'Theme and styling configuration',
    };
  }

  return data;
}

/**
 * 简单的 YAML 转换器
 */
function convertToYaml(obj: any, indent: number = 0): string {
  const spaces = '  '.repeat(indent);
  let yaml = '';

  if (Array.isArray(obj)) {
    obj.forEach((item) => {
      yaml += `${spaces}- `;
      if (typeof item === 'object' && item !== null) {
        yaml += '\n' + convertToYaml(item, indent + 1);
      } else {
        yaml += `${formatYamlValue(item)}\n`;
      }
    });
  } else if (typeof obj === 'object' && obj !== null) {
    Object.entries(obj).forEach(([key, value]) => {
      yaml += `${spaces}${key}: `;
      if (typeof value === 'object' && value !== null) {
        yaml += '\n' + convertToYaml(value, indent + 1);
      } else {
        yaml += `${formatYamlValue(value)}\n`;
      }
    });
  }

  return yaml;
}

/**
 * 格式化 YAML 值
 */
function formatYamlValue(value: any): string {
  if (typeof value === 'string') {
    // 如果字符串包含特殊字符，需要加引号
    if (value.includes('\n') || value.includes('"') || value.includes("'")) {
      return `"${value.replace(/"/g, '\\"')}"`;
    }
    return value;
  }
  return String(value);
}

/**
 * 简单的 XML 转换器
 */
function convertToXml(obj: any, indent: number = 0): string {
  const spaces = '  '.repeat(indent);
  let xml = '';

  if (Array.isArray(obj)) {
    obj.forEach((item, index) => {
      xml += `${spaces}<item index="${index}">\n`;
      xml += convertToXml(item, indent + 1);
      xml += `${spaces}</item>\n`;
    });
  } else if (typeof obj === 'object' && obj !== null) {
    Object.entries(obj).forEach(([key, value]) => {
      if (typeof value === 'object' && value !== null) {
        xml += `${spaces}<${key}>\n`;
        xml += convertToXml(value, indent + 1);
        xml += `${spaces}</${key}>\n`;
      } else {
        xml += `${spaces}<${key}>${escapeXml(String(value))}</${key}>\n`;
      }
    });
  }

  return xml;
}

/**
 * 转义 XML 特殊字符
 */
function escapeXml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * 简单的 YAML 解析器
 */
function parseYaml(yamlStr: string): CanvasSchema {
  // 这是一个非常简单的 YAML 解析实现
  // 在实际项目中，建议使用 js-yaml 库
  const lines = yamlStr.split('\n').filter((line) => line.trim() && !line.trim().startsWith('#'));
  const result: any = {};
  let currentObj = result;
  const stack: any[] = [result];

  lines.forEach((line) => {
    const indent = line.search(/\S/);
    const content = line.trim();

    if (content.includes(':')) {
      const [key, ...valueParts] = content.split(':');
      const value = valueParts.join(':').trim();

      // 调整堆栈深度
      while (stack.length > Math.floor(indent / 2) + 1) {
        stack.pop();
      }
      currentObj = stack[stack.length - 1];

      if (value) {
        currentObj[key.trim()] = parseYamlValue(value);
      } else {
        currentObj[key.trim()] = {};
        stack.push(currentObj[key.trim()]);
      }
    }
  });

  return result as CanvasSchema;
}

/**
 * 解析 YAML 值
 */
function parseYamlValue(value: string): any {
  value = value.trim();

  // 布尔值
  if (value === 'true') return true;
  if (value === 'false') return false;

  // 数字
  if (/^\d+$/.test(value)) return parseInt(value, 10);
  if (/^\d+\.\d+$/.test(value)) return parseFloat(value);

  // 字符串
  if (value.startsWith('"') && value.endsWith('"')) {
    return value.slice(1, -1).replace(/\\"/g, '"');
  }

  return value;
}

/**
 * 简单的 XML 解析器
 */
function parseXml(xmlStr: string): CanvasSchema {
  // 这是一个非常简单的 XML 解析实现
  // 在实际项目中，建议使用 DOMParser 或专门的 XML 解析库

  // 移除 XML 声明
  xmlStr = xmlStr.replace(/<\?xml[^>]*\?>/g, '');

  // 简单的标签匹配
  const tagRegex = /<(\w+)>(.*?)<\/\1>/gs;
  const result: any = {};

  let match;
  while ((match = tagRegex.exec(xmlStr)) !== null) {
    const [, tagName, content] = match;

    if (content.includes('<')) {
      // 嵌套标签
      result[tagName] = parseXml(`<root>${content}</root>`);
    } else {
      // 文本内容
      result[tagName] = unescapeXml(content.trim());
    }
  }

  return result as CanvasSchema;
}

/**
 * 反转义 XML 特殊字符
 */
function unescapeXml(str: string): string {
  return str
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
}

/**
 * 合并两个 Schema
 */
function mergeSchemas(base: CanvasSchema, override: CanvasSchema): CanvasSchema {
  return {
    ...base,
    ...override,
    components: override.components?.length ? override.components : base.components,
    layout: override.layout ? { ...base.layout, ...override.layout } : base.layout,
    theme: override.theme ? { ...base.theme, ...override.theme } : base.theme,
    metadata: override.metadata ? { ...base.metadata, ...override.metadata } : base.metadata,
  };
}

/**
 * 导出为文件下载
 */
export function downloadSchema(
  schema: CanvasSchema,
  filename: string,
  options: ExportOptions = { format: 'json' },
): void {
  try {
    const content = exportSchema(schema, options);
    const blob = new Blob([content], {
      type: getContentType(options.format),
    });

    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${filename}.${options.format}`;

    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);

    URL.revokeObjectURL(url);
  } catch (error) {
    throw new Error(`Download failed: ${error instanceof Error ? error.message : 'Unknown error'}`);
  }
}

/**
 * 从文件导入 Schema
 */
export function importSchemaFromFile(
  file: File,
  options: ImportOptions = {},
): Promise<ImportResult> {
  return new Promise((resolve) => {
    const reader = new FileReader();

    reader.onload = (event) => {
      try {
        const content = event.target?.result as string;
        const result = importSchema(content, options);
        resolve(result);
      } catch (error) {
        resolve({
          success: false,
          error: `File read failed: ${error instanceof Error ? error.message : 'Unknown error'}`,
        });
      }
    };

    reader.onerror = () => {
      resolve({
        success: false,
        error: 'Failed to read file',
      });
    };

    reader.readAsText(file);
  });
}

/**
 * 获取内容类型
 */
function getContentType(format: ExportFormat): string {
  switch (format) {
    case 'json':
      return 'application/json';
    case 'yaml':
      return 'application/x-yaml';
    case 'xml':
      return 'application/xml';
    default:
      return 'text/plain';
  }
}

/**
 * 创建 Schema 模板
 */
export function createSchemaTemplate(type: 'basic' | 'advanced' | 'custom'): CanvasSchema {
  const baseSchema = createDefaultSchema();

  switch (type) {
    case 'basic':
      return {
        ...baseSchema,
        components: [
          {
            id: 'input_1',
            type: 'input',
            props: { label: '姓名', required: true },
            styles: {},
            children: [],
          },
          {
            id: 'input_2',
            type: 'input',
            props: { label: '邮箱', type: 'email', required: true },
            styles: {},
            children: [],
          },
          {
            id: 'button_1',
            type: 'button',
            props: { text: '提交', type: 'primary' },
            styles: {},
            children: [],
          },
        ],
      };

    case 'advanced':
      return {
        ...baseSchema,
        components: [
          {
            id: 'container_1',
            type: 'container',
            props: { title: '用户信息' },
            styles: {},
            children: [
              {
                id: 'input_1',
                type: 'input',
                props: { label: '姓名', required: true },
                styles: {},
                children: [],
              },
              {
                id: 'select_1',
                type: 'select',
                props: {
                  label: '性别',
                  options: [
                    { label: '男', value: 'male' },
                    { label: '女', value: 'female' },
                  ],
                },
                styles: {},
                children: [],
              },
            ],
          },
        ],
      };

    default:
      return baseSchema;
  }
}
