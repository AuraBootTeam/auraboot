package com.auraboot.framework.infrastructure.storage.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import com.auraboot.framework.file.constant.StorageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OssStorageProviderTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "uploads/test-file.txt";

    @Mock
    private OSS ossClient;

    private OssStorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OssStorageProvider(ossClient, BUCKET);
    }

    @Test
    void type_returnsOss() {
        assertEquals(StorageType.OSS, provider.type());
    }

    @Test
    void upload_putsObjectAndReturnsOssUri() {
        byte[] content = "hello world".getBytes();
        InputStream input = new ByteArrayInputStream(content);
        when(ossClient.putObject(eq(BUCKET), eq(KEY), any(InputStream.class), any(ObjectMetadata.class)))
                .thenReturn(new PutObjectResult());

        String result = provider.upload(KEY, input, content.length, "text/plain");

        assertEquals("oss://test-bucket/uploads/test-file.txt", result);

        ArgumentCaptor<ObjectMetadata> metadataCaptor = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(ossClient).putObject(eq(BUCKET), eq(KEY), any(InputStream.class), metadataCaptor.capture());
        ObjectMetadata captured = metadataCaptor.getValue();
        assertEquals("text/plain", captured.getContentType());
        assertEquals(content.length, captured.getContentLength());
    }

    @Test
    void download_returnsObjectContent() {
        InputStream expectedStream = new ByteArrayInputStream("data".getBytes());
        OSSObject ossObject = new OSSObject();
        ossObject.setObjectContent(expectedStream);
        when(ossClient.getObject(BUCKET, KEY)).thenReturn(ossObject);

        InputStream result = provider.download(KEY);

        assertSame(expectedStream, result);
        verify(ossClient).getObject(BUCKET, KEY);
    }

    @Test
    void delete_deletesObject() {
        provider.delete(KEY);

        verify(ossClient).deleteObject(BUCKET, KEY);
    }

    @Test
    void getPresignedUrl_generatesUrl() throws Exception {
        URL expectedUrl = new URL("https://test-bucket.oss-cn-hangzhou.aliyuncs.com/uploads/test-file.txt?Expires=123");
        when(ossClient.generatePresignedUrl(eq(BUCKET), eq(KEY), any(Date.class)))
                .thenReturn(expectedUrl);

        String result = provider.getPresignedUrl(KEY, Duration.ofHours(1));

        assertEquals(expectedUrl.toString(), result);
        verify(ossClient).generatePresignedUrl(eq(BUCKET), eq(KEY), any(Date.class));
    }

    @Test
    void exists_returnsTrueWhenObjectExists() {
        when(ossClient.doesObjectExist(BUCKET, KEY)).thenReturn(true);

        assertTrue(provider.exists(KEY));
        verify(ossClient).doesObjectExist(BUCKET, KEY);
    }

    @Test
    void exists_returnsFalseWhenObjectDoesNotExist() {
        when(ossClient.doesObjectExist(BUCKET, KEY)).thenReturn(false);

        assertFalse(provider.exists(KEY));
    }

    @Test
    void destroy_shutsDownClient() throws Exception {
        provider.destroy();

        verify(ossClient).shutdown();
    }
}
