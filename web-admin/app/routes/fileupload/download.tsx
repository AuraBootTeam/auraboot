import { redirect } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import path from 'path';
import fs from 'fs/promises';
import { getFileById } from '~/shared/services/fileupload/fileService';
import { getServerUploadConfig } from '~/config/upload.config';

export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { fileId } = params;

  if (!fileId) {
    throw new Response('文件ID缺失', { status: 400 });
  }

  try {
    // TODO 可以增加权限校验,比如说  只有文件的上传者或者管理员可以下载
    const fileRecord = await getFileById(fileId!, request);
    if (!fileRecord) {
      throw new Response('文件不存在', { status: 404 });
    }

    if (fileRecord.storageType === 'local') {
      // 本地文件下载 - 使用服务器端配置
      const config = getServerUploadConfig();
      const uploadDir = path.join(process.cwd(), config.local.uploadDir);
      const filePath = path.join(uploadDir, fileRecord.fileName);
      const fileBuffer = await fs.readFile(filePath);

      // 对文件名进行 URL 编码处理，支持中文文件名
      const encodedFileName = encodeURIComponent(fileRecord.originalName);

      return new Response(new Uint8Array(fileBuffer), {
        headers: {
          'Content-Type': fileRecord.mimeType || 'application/octet-stream',
          'Content-Disposition': `attachment; filename*=UTF-8''${encodedFileName}`,
          'Content-Length': fileRecord.fileSize.toString(),
        },
      });
    } else {
      // 云存储文件重定向
      return redirect(fileRecord.cloudPath!);
    }
  } catch (error) {
    console.error('文件下载失败:', error);
    throw new Response('文件下载失败', { status: 500 });
  }
};
