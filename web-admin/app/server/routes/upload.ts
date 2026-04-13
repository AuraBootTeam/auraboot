/**
 * BFF Upload Routes
 * Engineering-grade file upload with stream forwarding
 *
 * Architecture: Browser → BFF → Spring Boot (zero disk storage in BFF)
 * - Uses busboy for multipart parsing
 * - Streams directly to Spring Boot without temp files
 * - Proper error handling and timeout management
 * - Authentication header forwarding
 */

import { Router, type Request, type Response } from 'express';
import Busboy from 'busboy';
import axios, { type AxiosError } from 'axios';
import logger from '../utils/logger';
import { sessionStorage } from '~/shared/services/session';
import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';

const router = Router();

/**
 * Extract JWT token from session cookie
 */
async function extractJwtToken(req: Request): Promise<string | null> {
  try {
    const cookieHeader = req.headers.cookie || '';
    if (!cookieHeader) {
      return null;
    }

    const session = await sessionStorage.getSession(cookieHeader);
    const token = session.get(JWT_TOKEN_KEY);

    if (token && typeof token === 'string') {
      const tokenParts = token.split('.');
      if (tokenParts.length === 3) {
        return token;
      }
    }
    return null;
  } catch (error) {
    logger.warn({ error: (error as Error).message }, 'Failed to extract JWT token');
    return null;
  }
}
const SPRING_BOOT_URL = process.env.SPRING_BOOT_URL || 'http://localhost:6443';
const UPLOAD_TIMEOUT = 10 * 60 * 1000; // 10 minutes for large files

/**
 * Admin document upload endpoint
 * Streams file directly to Spring Boot without disk storage
 */
router.post('/admin/documents/upload', async (req: Request, res: Response) => {
  const startTime = Date.now();
  const requestId = Math.random().toString(36).substring(7);

  // Authenticate before processing the upload to avoid wasting bandwidth
  const jwtToken = await extractJwtToken(req);
  if (!jwtToken && !req.headers.authorization) {
    logger.warn({ requestId }, 'No JWT token found for document upload');
    return res.status(401).json({
      success: false,
      message: 'Authentication required',
    });
  }

  logger.info(
    {
      requestId,
      contentType: req.headers['content-type'],
      contentLength: req.headers['content-length'],
      userAgent: req.headers['user-agent'],
    },
    'Starting admin document upload',
  );

  // Validate content type
  if (!req.headers['content-type']?.includes('multipart/form-data')) {
    logger.warn(
      {
        requestId,
        contentType: req.headers['content-type'],
      },
      'Invalid content type for upload',
    );
    return res.status(400).json({
      success: false,
      message: 'Content-Type must be multipart/form-data',
    });
  }

  const busboy = Busboy({
    headers: req.headers,
    limits: {
      fileSize: 2 * 1024 * 1024 * 1024, // 2GB limit
      files: 1, // Only one file allowed
      fields: 20, // Reasonable limit for metadata fields
    },
  });

  let fileProcessed = false;
  let uploadError: Error | null = null;
  let currentFilename = 'unknown';
  const metadata: Record<string, string> = {};

  // Handle form fields (metadata)
  busboy.on('field', (fieldname: string, value: string) => {
    logger.debug({ requestId, fieldname, valueLength: value.length }, 'Received form field');
    metadata[fieldname] = value;
  });

  // Handle file upload
  busboy.on(
    'file',
    async (
      fieldname: string,
      fileStream: NodeJS.ReadableStream,
      info: { filename: string; encoding: string; mimeType: string },
    ) => {
      const { filename, encoding, mimeType } = info;
      currentFilename = filename; // Store filename in outer scope

      if (fileProcessed) {
        logger.warn({ requestId, filename }, 'Multiple files detected, ignoring additional file');
        fileStream.resume(); // Drain the stream
        return;
      }

      fileProcessed = true;

      logger.info(
        {
          requestId,
          filename,
          mimeType,
          encoding,
          metadata: Object.keys(metadata),
          infoObject: info,
        },
        'Processing file upload',
      );

      try {
        // Forward to Spring Boot with streaming
        const response = await axios.post(`${SPRING_BOOT_URL}/api/files/upload`, fileStream, {
          headers: {
            'Content-Type': 'application/octet-stream',
            'X-Filename': encodeURIComponent(filename),
            'X-Mime-Type': mimeType || 'application/octet-stream',
            'X-Request-Id': requestId,
            Authorization: req.headers.authorization || (jwtToken ? `Bearer ${jwtToken}` : ''),
            'X-User-Id': req.headers['x-user-id'] || '',
            'X-Tenant-Id': req.headers['x-tenant-id'] || '',
            // Forward metadata as headers
            ...Object.entries(metadata).reduce(
              (acc, [key, value]) => {
                acc[`X-Meta-${key}`] = encodeURIComponent(value);
                return acc;
              },
              {} as Record<string, string>,
            ),
          },
          maxBodyLength: Infinity,
          timeout: UPLOAD_TIMEOUT,
          responseType: 'json',
        });

        const duration = Date.now() - startTime;
        logger.info(
          {
            requestId,
            filename,
            duration,
            responseStatus: response.status,
          },
          'Upload completed successfully',
        );

        res.json({
          success: true,
          data: response.data,
          metadata: {
            requestId,
            filename: currentFilename,
            duration,
            processedAt: new Date().toISOString(),
          },
        });
      } catch (error) {
        uploadError = error as Error;
        const duration = Date.now() - startTime;

        if (axios.isAxiosError(error)) {
          const axiosError = error as AxiosError;
          logger.error(
            {
              requestId,
              filename: currentFilename,
              duration,
              status: axiosError.response?.status,
              statusText: axiosError.response?.statusText,
              data: axiosError.response?.data,
              message: axiosError.message,
            },
            'Spring Boot upload failed',
          );

          const statusCode = axiosError.response?.status || 500;
          const errorData = axiosError.response?.data as any;
          const errorMessage = errorData?.message || axiosError.message || 'Upload failed';

          res.status(statusCode).json({
            success: false,
            message: errorMessage,
            metadata: {
              requestId,
              filename: currentFilename,
              duration,
              errorType: 'spring_boot_error',
            },
          });
        } else {
          const err = error as Error;
          logger.error(
            {
              requestId,
              filename: currentFilename,
              duration,
              error: err.message,
              stack: err.stack,
            },
            'Unexpected upload error',
          );

          res.status(500).json({
            success: false,
            message: 'Internal server error during upload',
            metadata: {
              requestId,
              filename: currentFilename,
              duration,
              errorType: 'internal_error',
            },
          });
        }
      }
    },
  );

  // Handle busboy errors
  busboy.on('error', (error: Error) => {
    const duration = Date.now() - startTime;
    logger.error(
      {
        requestId,
        duration,
        error: error.message,
        stack: error.stack,
      },
      'Busboy parsing error',
    );

    if (!res.headersSent) {
      res.status(400).json({
        success: false,
        message: 'File parsing error: ' + error.message,
        metadata: {
          requestId,
          duration,
          errorType: 'parsing_error',
        },
      });
    }
  });

  // Handle completion
  busboy.on('finish', () => {
    if (!fileProcessed && !uploadError) {
      const duration = Date.now() - startTime;
      logger.warn({ requestId, duration }, 'No file received in upload request');

      if (!res.headersSent) {
        res.status(400).json({
          success: false,
          message: 'No file provided in upload request',
          metadata: {
            requestId,
            duration,
            errorType: 'no_file',
          },
        });
      }
    }
  });

  // Handle request abortion
  req.on('aborted', () => {
    const duration = Date.now() - startTime;
    logger.info({ requestId, duration }, 'Upload request aborted by client');
  });

  // Set timeout for the entire request
  req.setTimeout(UPLOAD_TIMEOUT, () => {
    const duration = Date.now() - startTime;
    logger.error({ requestId, duration, timeout: UPLOAD_TIMEOUT }, 'Upload request timeout');

    if (!res.headersSent) {
      res.status(408).json({
        success: false,
        message: 'Upload timeout',
        metadata: {
          requestId,
          duration,
          errorType: 'timeout',
        },
      });
    }
  });

  // Start processing
  req.pipe(busboy);
});

/**
 * Generic file upload endpoint for SmartUpload form fields.
 * Forwards multipart data to Spring Boot /api/file/upload.
 */
router.post('/file/upload', async (req: Request, res: Response) => {
  const startTime = Date.now();
  const requestId = Math.random().toString(36).substring(7);

  const jwtToken = await extractJwtToken(req);
  if (!jwtToken && !req.headers.authorization) {
    return res.status(401).json({ success: false, message: 'Authentication required' });
  }

  if (!req.headers['content-type']?.includes('multipart/form-data')) {
    return res
      .status(400)
      .json({ success: false, message: 'Content-Type must be multipart/form-data' });
  }

  const busboy = Busboy({
    headers: req.headers,
    limits: { fileSize: 100 * 1024 * 1024, files: 1, fields: 10 },
  });

  let fileProcessed = false;
  let uploadError: Error | null = null;

  busboy.on(
    'file',
    async (
      _fieldname: string,
      fileStream: NodeJS.ReadableStream,
      info: { filename: string; encoding: string; mimeType: string },
    ) => {
      if (fileProcessed) {
        fileStream.resume();
        return;
      }
      fileProcessed = true;

      try {
        // Collect chunks and forward as multipart to Spring Boot
        const chunks: Buffer[] = [];
        for await (const chunk of fileStream) {
          chunks.push(chunk as Buffer);
        }
        const fileBuffer = Buffer.concat(chunks);

        const FormData = (await import('form-data')).default;
        const form = new FormData();
        form.append('file', fileBuffer, { filename: info.filename, contentType: info.mimeType });

        const response = await axios.post(`${SPRING_BOOT_URL}/api/file/upload`, form, {
          headers: {
            ...form.getHeaders(),
            Authorization: req.headers.authorization || (jwtToken ? `Bearer ${jwtToken}` : ''),
          },
          maxBodyLength: Infinity,
          timeout: UPLOAD_TIMEOUT,
        });

        res.json(response.data);
      } catch (error) {
        uploadError = error as Error;
        if (!res.headersSent) {
          if (axios.isAxiosError(error)) {
            const status = (error as AxiosError).response?.status || 500;
            res
              .status(status)
              .json(
                (error as AxiosError).response?.data || { success: false, message: error.message },
              );
          } else {
            res
              .status(500)
              .json({ success: false, message: 'Internal server error during upload' });
          }
        }
      }
    },
  );

  busboy.on('error', (error: Error) => {
    if (!res.headersSent) {
      res.status(400).json({ success: false, message: 'File parsing error: ' + error.message });
    }
  });

  busboy.on('finish', () => {
    if (!fileProcessed && !uploadError && !res.headersSent) {
      res.status(400).json({ success: false, message: 'No file provided' });
    }
  });

  req.setTimeout(UPLOAD_TIMEOUT, () => {
    if (!res.headersSent) {
      res.status(408).json({ success: false, message: 'Upload timeout' });
    }
  });

  req.pipe(busboy);
});

/**
 * Health check for upload service
 */
router.get('/upload/health', (req: Request, res: Response) => {
  res.json({
    status: 'healthy',
    service: 'bff-upload',
    timestamp: new Date().toISOString(),
    springBootTarget: SPRING_BOOT_URL,
    uploadTimeout: UPLOAD_TIMEOUT,
  });
});

/**
 * Plugin package upload endpoint
 * Streams unified plugin package (ZIP) directly to Spring Boot
 */
router.post('/plugins/packages/upload', async (req: Request, res: Response) => {
  const startTime = Date.now();
  const requestId = Math.random().toString(36).substring(7);

  logger.info(
    {
      requestId,
      contentType: req.headers['content-type'],
      contentLength: req.headers['content-length'],
    },
    'Starting plugin package upload',
  );

  // Extract JWT token from session
  const jwtToken = await extractJwtToken(req);
  if (!jwtToken) {
    logger.warn({ requestId }, 'No JWT token found in session');
    return res.status(401).json({
      success: false,
      message: 'Authentication required',
    });
  }

  // Validate content type
  if (!req.headers['content-type']?.includes('multipart/form-data')) {
    logger.warn(
      {
        requestId,
        contentType: req.headers['content-type'],
      },
      'Invalid content type for plugin upload',
    );
    return res.status(400).json({
      success: false,
      message: 'Content-Type must be multipart/form-data',
    });
  }

  const busboy = Busboy({
    headers: req.headers,
    limits: {
      fileSize: 500 * 1024 * 1024, // 500MB limit for plugin packages
      files: 1,
      fields: 10,
    },
  });

  let fileProcessed = false;
  let uploadError: Error | null = null;
  let currentFilename = 'unknown';
  const formFields: Record<string, string> = {};

  // Handle form fields
  busboy.on('field', (fieldname: string, value: string) => {
    logger.debug({ requestId, fieldname }, 'Received form field');
    formFields[fieldname] = value;
  });

  // Handle file upload
  busboy.on(
    'file',
    async (
      fieldname: string,
      fileStream: NodeJS.ReadableStream,
      info: { filename: string; encoding: string; mimeType: string },
    ) => {
      const { filename, mimeType } = info;
      currentFilename = filename;

      if (fileProcessed) {
        logger.warn({ requestId, filename }, 'Multiple files detected, ignoring');
        fileStream.resume();
        return;
      }

      fileProcessed = true;

      logger.info(
        {
          requestId,
          filename,
          mimeType,
        },
        'Processing plugin package file',
      );

      try {
        // Forward to Spring Boot
        const response = await axios.post(
          `${SPRING_BOOT_URL}/api/plugins/packages/upload`,
          fileStream,
          {
            headers: {
              'Content-Type': 'application/octet-stream',
              'X-Filename': encodeURIComponent(filename),
              'X-Mime-Type': mimeType || 'application/octet-stream',
              'X-Request-Id': requestId,
              Authorization: `Bearer ${jwtToken}`,
            },
            maxBodyLength: Infinity,
            timeout: UPLOAD_TIMEOUT,
            responseType: 'json',
          },
        );

        const duration = Date.now() - startTime;
        logger.info(
          {
            requestId,
            filename,
            duration,
            responseStatus: response.status,
          },
          'Plugin package upload completed',
        );

        res.json(response.data);
      } catch (error) {
        uploadError = error as Error;
        const duration = Date.now() - startTime;

        if (axios.isAxiosError(error)) {
          const axiosError = error as AxiosError;
          logger.error(
            {
              requestId,
              filename: currentFilename,
              duration,
              status: axiosError.response?.status,
              data: axiosError.response?.data,
            },
            'Plugin package upload failed',
          );

          const statusCode = axiosError.response?.status || 500;
          const errorData = axiosError.response?.data as any;

          res.status(statusCode).json(
            errorData || {
              success: false,
              message: axiosError.message,
            },
          );
        } else {
          logger.error(
            {
              requestId,
              filename: currentFilename,
              duration,
              error: (error as Error).message,
            },
            'Unexpected plugin upload error',
          );

          res.status(500).json({
            success: false,
            message: 'Internal server error during upload',
          });
        }
      }
    },
  );

  busboy.on('error', (error: Error) => {
    logger.error({ requestId, error: error.message }, 'Busboy parsing error');
    if (!res.headersSent) {
      res.status(400).json({
        success: false,
        message: 'File parsing error: ' + error.message,
      });
    }
  });

  busboy.on('finish', () => {
    if (!fileProcessed && !uploadError) {
      logger.warn({ requestId }, 'No file received in plugin upload request');
      if (!res.headersSent) {
        res.status(400).json({
          success: false,
          message: 'No file provided in upload request',
        });
      }
    }
  });

  req.on('aborted', () => {
    logger.info({ requestId }, 'Plugin upload request aborted');
  });

  req.setTimeout(UPLOAD_TIMEOUT, () => {
    logger.error({ requestId }, 'Plugin upload request timeout');
    if (!res.headersSent) {
      res.status(408).json({
        success: false,
        message: 'Upload timeout',
      });
    }
  });

  req.pipe(busboy);
});

/**
 * Plugin package install (upload and install in one step)
 */
router.post('/plugins/packages/install', async (req: Request, res: Response) => {
  const startTime = Date.now();
  const requestId = Math.random().toString(36).substring(7);

  logger.info(
    {
      requestId,
      contentType: req.headers['content-type'],
    },
    'Starting plugin package install',
  );

  // Extract JWT token from session
  const jwtToken = await extractJwtToken(req);
  if (!jwtToken) {
    logger.warn({ requestId }, 'No JWT token found in session');
    return res.status(401).json({
      success: false,
      message: 'Authentication required',
    });
  }

  if (!req.headers['content-type']?.includes('multipart/form-data')) {
    return res.status(400).json({
      success: false,
      message: 'Content-Type must be multipart/form-data',
    });
  }

  const busboy = Busboy({
    headers: req.headers,
    limits: {
      fileSize: 500 * 1024 * 1024,
      files: 1,
      fields: 10,
    },
  });

  let uploadError: Error | null = null;
  let currentFilename = 'unknown';
  const formFields: Record<string, string> = {};
  // Buffer file data since field events may arrive after file event
  let fileBuffer: Buffer | null = null;
  let fileMimeType = 'application/octet-stream';

  busboy.on('field', (fieldname: string, value: string) => {
    formFields[fieldname] = value;
  });

  busboy.on(
    'file',
    (
      fieldname: string,
      fileStream: NodeJS.ReadableStream,
      info: { filename: string; encoding: string; mimeType: string },
    ) => {
      const { filename, mimeType } = info;
      currentFilename = filename;
      fileMimeType = mimeType || 'application/octet-stream';

      // Buffer the file data - we'll send it after all fields are parsed
      const chunks: Buffer[] = [];
      fileStream.on('data', (chunk: Buffer) => chunks.push(chunk));
      fileStream.on('end', () => {
        fileBuffer = Buffer.concat(chunks);
      });
    },
  );

  busboy.on('error', (error: Error) => {
    if (!res.headersSent) {
      res.status(400).json({
        success: false,
        message: 'File parsing error: ' + error.message,
      });
    }
  });

  busboy.on('finish', async () => {
    if (!fileBuffer) {
      if (!uploadError && !res.headersSent) {
        res.status(400).json({
          success: false,
          message: 'No file provided',
        });
      }
      return;
    }

    logger.info(
      { requestId, filename: currentFilename, fields: Object.keys(formFields) },
      'Processing plugin package for install',
    );

    try {
      const response = await axios.post(
        `${SPRING_BOOT_URL}/api/plugins/packages/install`,
        fileBuffer,
        {
          headers: {
            'Content-Type': 'application/octet-stream',
            'X-Filename': encodeURIComponent(currentFilename),
            'X-Mime-Type': fileMimeType,
            'X-Request-Id': requestId,
            Authorization: `Bearer ${jwtToken}`,
            // Forward install options as headers (all fields parsed by now)
            'X-Skip-Config': formFields['skipConfig'] || 'false',
            'X-Skip-Backend': formFields['skipBackend'] || 'false',
            'X-Skip-Frontend': formFields['skipFrontend'] || 'false',
            'X-Force-Overwrite': formFields['forceOverwrite'] || 'false',
            'X-Auto-Enable': formFields['autoEnable'] || 'true',
          },
          maxBodyLength: Infinity,
          timeout: UPLOAD_TIMEOUT,
          responseType: 'json',
        },
      );

      const duration = Date.now() - startTime;
      logger.info(
        { requestId, filename: currentFilename, duration },
        'Plugin package install completed',
      );

      res.json(response.data);
    } catch (error) {
      uploadError = error as Error;

      if (axios.isAxiosError(error)) {
        const axiosError = error as AxiosError;
        logger.error(
          {
            requestId,
            filename: currentFilename,
            status: axiosError.response?.status,
            data: axiosError.response?.data,
          },
          'Plugin package install failed',
        );

        const statusCode = axiosError.response?.status || 500;
        const errorData = axiosError.response?.data as any;

        res.status(statusCode).json(
          errorData || {
            success: false,
            message: axiosError.message,
          },
        );
      } else {
        res.status(500).json({
          success: false,
          message: 'Internal server error during install',
        });
      }
    }
  });

  req.setTimeout(UPLOAD_TIMEOUT, () => {
    if (!res.headersSent) {
      res.status(408).json({ success: false, message: 'Upload timeout' });
    }
  });

  req.pipe(busboy);
});

export default router;
