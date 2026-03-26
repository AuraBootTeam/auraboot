package com.auraboot.framework.infrastructure.storage.s3;

import com.auraboot.framework.file.constant.StorageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3StorageProviderTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "uploads/test-file.txt";

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private S3StorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new S3StorageProvider(s3Client, s3Presigner, BUCKET);
    }

    @Test
    @DisplayName("type() returns S3")
    void type_returnsS3() {
        assertEquals(StorageType.S3, provider.type());
    }

    @Test
    @DisplayName("upload() puts object with correct bucket, key and contentType")
    void upload_putsObjectCorrectly() {
        byte[] content = "hello s3".getBytes();
        InputStream input = new ByteArrayInputStream(content);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String result = provider.upload(KEY, input, content.length, "text/plain");

        assertEquals("s3://test-bucket/uploads/test-file.txt", result);

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));
        PutObjectRequest captured = reqCaptor.getValue();
        assertEquals(BUCKET, captured.bucket());
        assertEquals(KEY, captured.key());
        assertEquals("text/plain", captured.contentType());
    }

    @Test
    @DisplayName("download() returns input stream from S3")
    @SuppressWarnings("unchecked")
    void download_returnsInputStream() {
        ResponseInputStream<GetObjectResponse> mockStream = mock(ResponseInputStream.class);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);

        InputStream result = provider.download(KEY);

        assertSame(mockStream, result);

        ArgumentCaptor<GetObjectRequest> reqCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(reqCaptor.capture());
        assertEquals(BUCKET, reqCaptor.getValue().bucket());
        assertEquals(KEY, reqCaptor.getValue().key());
    }

    @Test
    @DisplayName("delete() removes object from S3")
    void delete_removesObject() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        provider.delete(KEY);

        ArgumentCaptor<DeleteObjectRequest> reqCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(reqCaptor.capture());
        assertEquals(BUCKET, reqCaptor.getValue().bucket());
        assertEquals(KEY, reqCaptor.getValue().key());
    }

    @Test
    @DisplayName("getPresignedUrl() returns pre-signed URL with correct duration")
    void getPresignedUrl_returnsUrl() throws Exception {
        Duration expiry = Duration.ofMinutes(15);
        String expectedUrl = "https://test-bucket.s3.amazonaws.com/uploads/test-file.txt?X-Amz-Signature=abc";

        PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
        when(presignedRequest.url()).thenReturn(new URL(expectedUrl));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

        String result = provider.getPresignedUrl(KEY, expiry);

        assertEquals(expectedUrl, result);

        ArgumentCaptor<GetObjectPresignRequest> reqCaptor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(reqCaptor.capture());
        GetObjectPresignRequest captured = reqCaptor.getValue();
        assertEquals(expiry, captured.signatureDuration());
        assertEquals(BUCKET, captured.getObjectRequest().bucket());
        assertEquals(KEY, captured.getObjectRequest().key());
    }

    @Test
    @DisplayName("exists() returns true when object exists")
    void exists_returnsTrue_whenObjectExists() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        assertTrue(provider.exists(KEY));

        ArgumentCaptor<HeadObjectRequest> reqCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(reqCaptor.capture());
        assertEquals(BUCKET, reqCaptor.getValue().bucket());
        assertEquals(KEY, reqCaptor.getValue().key());
    }

    @Test
    @DisplayName("exists() returns false when NoSuchKeyException is thrown")
    void exists_returnsFalse_whenNoSuchKey() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertFalse(provider.exists(KEY));
    }

    @Test
    @DisplayName("destroy() closes both S3Client and S3Presigner")
    void destroy_closesBothClients() {
        provider.destroy();

        verify(s3Client).close();
        verify(s3Presigner).close();
    }
}
