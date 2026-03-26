import type { LoaderFunctionArgs } from 'react-router';
import { createReadStream } from 'fs';
import { join } from 'path';
import { stat } from 'fs/promises';
import { getServerUploadConfig } from '~/config/upload.config';

export async function loader({ params }: LoaderFunctionArgs) {
  const { filename } = params;

  if (!filename) {
    throw new Response('Filename is required', { status: 400 });
  }

  const config = getServerUploadConfig();
  const filePath = join(process.cwd(), config.local.uploadDir, filename);

  try {
    const fileStat = await stat(filePath);

    if (!fileStat.isFile()) {
      throw new Response('File not found', { status: 404 });
    }

    const stream = createReadStream(filePath);

    // 根据文件扩展名设置正确的Content-Type
    const ext = filename.split('.').pop()?.toLowerCase();
    let contentType = 'application/octet-stream';

    switch (ext) {
      case 'jpg':
      case 'jpeg':
        contentType = 'image/jpeg';
        break;
      case 'png':
        contentType = 'image/png';
        break;
      case 'gif':
        contentType = 'image/gif';
        break;
      case 'webp':
        contentType = 'image/webp';
        break;
      case 'svg':
        contentType = 'image/svg+xml';
        break;
    }

    return new Response(stream as any, {
      headers: {
        'Content-Type': contentType,
        'Content-Length': fileStat.size.toString(),
        'Cache-Control': 'public, max-age=31536000', // 缓存1年
      },
    });
  } catch (error) {
    throw new Response('File not found', { status: 404 });
  }
}
