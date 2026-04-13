/**
 * File upload utilities.
 * All file uploads go through the BFF proxy using standard fetch.
 * Authentication is handled via httpOnly session cookie — no localStorage token needed.
 */

/**
 * Upload a single file through the BFF proxy.
 */
export async function uploadFile(file: File): Promise<Response> {
  const formData = new FormData();
  formData.append('file', file);

  return fetch('/api/file/upload', {
    method: 'post',
    body: formData,
    // No Content-Type header — browser sets it with boundary for multipart
    // No Authorization header — session cookie is sent automatically
  });
}

/**
 * Upload multiple files through the BFF proxy.
 */
export async function uploadFiles(files: File[]): Promise<Response> {
  const formData = new FormData();
  for (const file of files) {
    formData.append('files', file);
  }

  return fetch('/api/file/upload-batch', {
    method: 'post',
    body: formData,
  });
}
