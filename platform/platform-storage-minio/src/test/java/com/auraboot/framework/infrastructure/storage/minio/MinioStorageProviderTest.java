package com.auraboot.framework.infrastructure.storage.minio;

import com.auraboot.framework.file.constant.StorageType;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MinioStorageProvider}.
 * Uses Mockito to mock MinioClient — no real MinIO server required.
 */
@ExtendWith(MockitoExtension.class)
class MinioStorageProviderTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "uploads/2026/02/test-file.pdf";
    private static final String CONTENT_TYPE = "application/pdf";

    @Mock
    private MinioClient minioClient;

    private MinioStorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MinioStorageProvider(minioClient, BUCKET);
    }

    @Test
    @DisplayName("type() returns MINIO")
    void typeReturnsMinio() {
        assertEquals(StorageType.MINIO, provider.type());
    }

    @Test
    @DisplayName("upload() calls putObject with correct arguments")
    void uploadCallsPutObject() throws Exception {
        byte[] content = "hello world".getBytes();
        InputStream input = new ByteArrayInputStream(content);

        String result = provider.upload(KEY, input, content.length, CONTENT_TYPE);

        assertEquals(KEY, result);

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());

        PutObjectArgs args = captor.getValue();
        assertEquals(BUCKET, args.bucket());
        assertEquals(KEY, args.object());
        assertEquals(CONTENT_TYPE, args.contentType());
    }

    @Test
    @DisplayName("upload() wraps exception in StorageException")
    void uploadWrapsException() throws Exception {
        doThrow(new RuntimeException("network error"))
                .when(minioClient).putObject(any(PutObjectArgs.class));

        byte[] content = "data".getBytes();
        InputStream input = new ByteArrayInputStream(content);

        MinioStorageProvider.StorageException ex = assertThrows(
                MinioStorageProvider.StorageException.class,
                () -> provider.upload(KEY, input, content.length, CONTENT_TYPE)
        );
        assertTrue(ex.getMessage().contains(KEY));
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("download() calls getObject and returns stream")
    void downloadReturnsStream() throws Exception {
        GetObjectResponse mockResponse = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        InputStream result = provider.download(KEY);

        assertNotNull(result);
        assertSame(mockResponse, result);

        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(captor.capture());
        assertEquals(BUCKET, captor.getValue().bucket());
        assertEquals(KEY, captor.getValue().object());
    }

    @Test
    @DisplayName("download() wraps exception in StorageException")
    void downloadWrapsException() throws Exception {
        doThrow(new RuntimeException("connection refused"))
                .when(minioClient).getObject(any(GetObjectArgs.class));

        assertThrows(
                MinioStorageProvider.StorageException.class,
                () -> provider.download(KEY)
        );
    }

    @Test
    @DisplayName("delete() calls removeObject with correct arguments")
    void deleteCallsRemoveObject() throws Exception {
        provider.delete(KEY);

        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(captor.capture());
        assertEquals(BUCKET, captor.getValue().bucket());
        assertEquals(KEY, captor.getValue().object());
    }

    @Test
    @DisplayName("delete() wraps exception in StorageException")
    void deleteWrapsException() throws Exception {
        doThrow(new RuntimeException("access denied"))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        assertThrows(
                MinioStorageProvider.StorageException.class,
                () -> provider.delete(KEY)
        );
    }

    @Test
    @DisplayName("getPresignedUrl() returns URL from MinIO client")
    void getPresignedUrlReturnsUrl() throws Exception {
        String expectedUrl = "https://minio.example.com/test-bucket/uploads/2026/02/test-file.pdf?X-Amz-Signature=abc123";
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn(expectedUrl);

        String url = provider.getPresignedUrl(KEY, Duration.ofHours(1));

        assertEquals(expectedUrl, url);

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioClient).getPresignedObjectUrl(captor.capture());

        GetPresignedObjectUrlArgs args = captor.getValue();
        assertEquals(BUCKET, args.bucket());
        assertEquals(KEY, args.object());
        assertEquals(Method.GET, args.method());
    }

    @Test
    @DisplayName("getPresignedUrl() wraps exception in StorageException")
    void getPresignedUrlWrapsException() throws Exception {
        doThrow(new RuntimeException("invalid key"))
                .when(minioClient).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));

        assertThrows(
                MinioStorageProvider.StorageException.class,
                () -> provider.getPresignedUrl(KEY, Duration.ofMinutes(30))
        );
    }

    @Test
    @DisplayName("exists() returns true when statObject succeeds")
    void existsReturnsTrueWhenObjectFound() throws Exception {
        StatObjectResponse mockStat = mock(StatObjectResponse.class);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(mockStat);

        assertTrue(provider.exists(KEY));

        ArgumentCaptor<StatObjectArgs> captor = ArgumentCaptor.forClass(StatObjectArgs.class);
        verify(minioClient).statObject(captor.capture());
        assertEquals(BUCKET, captor.getValue().bucket());
        assertEquals(KEY, captor.getValue().object());
    }

    @Test
    @DisplayName("exists() returns false when statObject throws ErrorResponseException")
    void existsReturnsFalseWhenObjectNotFound() throws Exception {
        ErrorResponseException notFound = new ErrorResponseException(
                new ErrorResponse("NoSuchKey", "Object not found", BUCKET, KEY, "", "", ""),
                okhttp3.Response.Builder.class.getName().isEmpty()
                        ? null
                        : null,
                ""
        );
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(notFound);

        assertFalse(provider.exists(KEY));
    }

    @Test
    @DisplayName("exists() wraps non-ErrorResponseException in StorageException")
    void existsWrapsOtherExceptions() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new RuntimeException("connection timeout"));

        assertThrows(
                MinioStorageProvider.StorageException.class,
                () -> provider.exists(KEY)
        );
    }
}
