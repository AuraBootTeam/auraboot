// app/routes/api.upload.aliyun.tsx
import type { ActionFunctionArgs } from 'react-router';
import OSS from 'ali-oss';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { getTokenFromRequest } from '~/shared/services/session';
import { saveFileToDatabase } from '~/shared/services/fileupload/fileService';
import { uploadConfig } from '~/config/upload.config';

// 阿里云配置接口
interface AliyunConfig {
  region: string;
  accessKeyId: string;
  accessKeySecret: string;
  bucket: string;
  cdnDomain?: string;
}

export const action = async ({ request }: ActionFunctionArgs) => {
  try {
    const formData = await request.formData();
    const file = formData.get('file') as File;
    const folder = (formData.get('folder') as string) || uploadConfig.aliyun.defaultFolder;

    if (!file) {
      return Response.json({ error: 'No file provided' }, { status: 400 });
    }

    // 从后端获取阿里云配置
    const token = await getTokenFromRequest(request);

    const configResult = await fetchResult<AliyunConfig>(`/api/config/aliyun-oss`, {
      method: 'get',
      params: {},
      token,
    });

    if (!ResultHelper.isSuccess(configResult) || !configResult.data) {
      return Response.json({ error: 'Failed to load Aliyun OSS configuration' }, { status: 500 });
    }

    const aliyunConfig = configResult.data;

    // 使用从后端获取的配置创建 OSS 客户端
    const _client = new OSS({
      region: aliyunConfig.region,
      accessKeyId: aliyunConfig.accessKeyId,
      accessKeySecret: aliyunConfig.accessKeySecret,
      bucket: aliyunConfig.bucket,
    });

    // 生成对象名
    const timestamp = Date.now();
    const randomStr = Math.random().toString(36).substring(2);
    const fileExtension = file.name.split('.').pop();
    const filename = `${timestamp}_${randomStr}.${fileExtension}`;
    const objectName = `${folder}/${filename}`;

    // 上传到OSS
    const _arrayBuffer = await file.arrayBuffer();

    // 获取CDN配置
    const cdnDomain =
      aliyunConfig.cdnDomain ||
      `https://${aliyunConfig.bucket}.oss-${aliyunConfig.region}.aliyuncs.com`;

    // 构建CDN URL
    const cdnUrl = `${cdnDomain}/${objectName}`;

    // 保存文件信息到数据库
    const fileRecord = await saveFileToDatabase(
      {
        fileName: filename,
        originalName: file.name,
        fileSize: file.size,
        mimeType: file.type,
        cloudPath: cdnUrl,
        storageType: 'aliyun',
      },
      request,
    );

    return Response.json({
      success: true,
      url: cdnUrl,
      fileId: fileRecord.fileId,
      filename: filename,
    });
  } catch (error) {
    console.error('阿里云上传失败:', error);
    return Response.json({ error: 'File upload failed' }, { status: 500 });
  }
};
