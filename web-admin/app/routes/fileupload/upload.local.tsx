// app/routes/api.upload.local.tsx
import type { ActionFunctionArgs } from 'react-router';
import { writeFile, mkdir } from 'fs/promises';
import { join } from 'path';
import { existsSync } from 'fs';
import { saveFileToDatabase } from '~/shared/services/fileupload/fileService';
import { getServerUploadConfig } from '~/config/upload.config';

export const action = async ({ request }: ActionFunctionArgs) => {
  try {
    const formData = await request.formData();
    const file = formData.get('file') as File;

    if (!file) {
      return Response.json({ error: 'No file provided' }, { status: 400 });
    }

    // 使用服务器端配置
    const config = getServerUploadConfig();
    const uploadDir = join(process.cwd(), config.local.uploadDir);
    if (!existsSync(uploadDir)) {
      await mkdir(uploadDir, { recursive: true });
    }

    // 生成唯一文件名
    const timestamp = Date.now();
    const randomStr = Math.random().toString(36).substring(2);
    const fileExtension = file.name.split('.').pop();
    const filename = `${timestamp}_${randomStr}.${fileExtension}`;

    // 保存文件
    const filePath = join(uploadDir, filename);
    const arrayBuffer = await file.arrayBuffer();
    await writeFile(filePath, Buffer.from(arrayBuffer));

    // 保存文件信息到数据库
    const fileRecord = await saveFileToDatabase(
      {
        fileName: filename,
        originalName: file.name,
        fileSize: file.size,
        mimeType: file.type,
        localPath: `${config.local.publicPath}/${filename}`,
        storageType: 'local',
      },
      request,
    );

    return Response.json({
      success: true,
      filename: filename,
      fileId: fileRecord.fileId,
      url: `${config.local.publicPath}/${filename}`,
      size: file.size,
    });
  } catch (error) {
    console.error('本地上传失败:', error);
    return Response.json({ error: 'File upload failed' }, { status: 500 });
  }
};
