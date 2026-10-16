package com.auraboot.framework.infrastructure.storage.cos;

import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.infrastructure.storage.StorageProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.exception.CosServiceException;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.io.*;
import java.net.URL;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic provider seams plus real SDK local signing. No COS API/network requests are made. */
class CosStorageProviderTest {
    COSClient client;CosStorageProvider provider;
    @BeforeEach void setup(){client=mock(COSClient.class);provider=new CosStorageProvider(client,"configured-1234567890");}
    @Test void configurationIsExplicitHttpsAndDoesNotRevealSecrets(){assertEquals(StorageType.COS,provider.type());assertEquals(HttpProtocol.https,CosStorageProvider.httpsConfig("ap-nanjing").getHttpProtocol());var annotation=CosStorageProvider.class.getAnnotation(ConditionalOnProperty.class);assertArrayEquals(new String[]{"aura.storage.type"},annotation.name());assertEquals("cos",annotation.havingValue());var properties=new StorageProperties();properties.getCos().setSecretId("test-id-not-a-real-credential");properties.getCos().setSecretKey("test-secret-not-a-real-credential");assertFalse(properties.toString().contains("test-secret"));assertFalse(properties.toString().contains("test-id"));assertThrows(IllegalArgumentException.class,()->new CosStorageProvider(properties));verifyNoInteractions(client);}
    @Test void uploadHasMetadataAndNeverCreatesBucket(){byte[] bytes={1,2,3};var input=new ByteArrayInputStream(bytes);assertEquals("cos://configured-1234567890/private/asset",provider.upload("private/asset",input,3,"image/png"));var metadata=ArgumentCaptor.forClass(ObjectMetadata.class);verify(client).putObject(eq("configured-1234567890"),eq("private/asset"),same(input),metadata.capture());assertEquals(3,metadata.getValue().getContentLength());assertEquals("image/png",metadata.getValue().getContentType());verifyNoMoreInteractions(client);}
    @Test void downloadDeleteExistsUseConfiguredScope()throws Exception {var stream=new ByteArrayInputStream(new byte[]{1});var object=new COSObject();object.setObjectContent(stream);when(client.getObject("configured-1234567890","key")).thenReturn(object);when(client.doesObjectExist("configured-1234567890","key")).thenReturn(true);assertArrayEquals(new byte[]{1},provider.download("key").readAllBytes());assertTrue(provider.exists("key"));provider.delete("key");verify(client).getObject("configured-1234567890","key");verify(client).doesObjectExist("configured-1234567890","key");verify(client).deleteObject("configured-1234567890","key");verifyNoMoreInteractions(client);}
    @Test void signedRequestIsGetScopedAndExpiresAtRequestedShortTime()throws Exception {when(client.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(new URL("https://configured-1234567890.cos.ap-nanjing.myqcloud.com/key?q-signature=unit"));Instant before=Instant.now();assertTrue(provider.getPresignedUrl("key",Duration.ofMinutes(5)).startsWith("https://"));var request=ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);verify(client).generatePresignedUrl(request.capture());assertEquals("configured-1234567890",request.getValue().getBucketName());assertEquals("key",request.getValue().getKey());assertEquals(HttpMethodName.GET,request.getValue().getMethod());assertTrue(request.getValue().getExpiration().toInstant().isAfter(before.plusSeconds(299)));assertTrue(request.getValue().getExpiration().toInstant().isBefore(Instant.now().plusSeconds(301)));}
    @Test void insecureUrlsAndInvalidExpiryAreRejectedWithoutSilentFallback()throws Exception {for(Duration d:new Duration[]{Duration.ZERO,Duration.ofSeconds(-1),Duration.ofHours(2)})assertThrows(IllegalArgumentException.class,()->provider.getPresignedUrl("key",d));assertThrows(IllegalArgumentException.class,()->provider.getPresignedUrl("key",null));verifyNoInteractions(client);when(client.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(new URL("http://insecure.test/key"));assertThrows(IllegalStateException.class,()->provider.getPresignedUrl("key",Duration.ofMinutes(5)));}
    @Test void realSdkSignsLocallyWithHttpsRegionAndEncodedObjectKey(){var sdk=new COSClient(new BasicCOSCredentials("unit-only-id","unit-only-secret"),CosStorageProvider.httpsConfig("ap-nanjing"));try{var local=new CosStorageProvider(sdk,"configured-1234567890");String signed=local.getPresignedUrl("private/孩子 photo.png",Duration.ofMinutes(5));var uri=java.net.URI.create(signed);assertEquals("https",uri.getScheme());assertEquals("configured-1234567890.cos.ap-nanjing.myqcloud.com",uri.getHost());assertTrue(uri.getRawPath().contains("%20"));assertTrue(uri.getQuery().contains("q-signature="));assertTrue(uri.getQuery().contains("q-key-time="));assertFalse(signed.contains("unit-only-secret"));}finally{sdk.shutdown();}}
    @Test void providerFailuresPropagateAndInvalidInputDoesNotWrite(){var failure=new CosServiceException("denied");when(client.putObject(anyString(),anyString(),any(InputStream.class),any(ObjectMetadata.class))).thenThrow(failure);assertSame(failure,assertThrows(CosServiceException.class,()->provider.upload("key",new ByteArrayInputStream(new byte[]{1}),1,"image/png")));assertThrows(IllegalArgumentException.class,()->provider.upload("key",null,1,"image/png"));assertThrows(IllegalArgumentException.class,()->provider.upload("key",new ByteArrayInputStream(new byte[]{1}),-1,"image/png"));verify(client,times(1)).putObject(anyString(),anyString(),any(InputStream.class),any(ObjectMetadata.class));}
    @Test void aclOwnerOnlyClassifierRejectsAnonymousAuthenticatedAndOtherAccounts(){
        var acl=new com.qcloud.cos.model.AccessControlList();acl.setOwner(new com.qcloud.cos.model.Owner("unit-owner","unit-owner"));
        acl.grantPermission(new com.qcloud.cos.model.UinGrantee("unit-owner"),com.qcloud.cos.model.Permission.FullControl);
        assertTrue(CosAclIT.ownerOnly(acl));
        acl.grantPermission(com.qcloud.cos.model.GroupGrantee.AllUsers,com.qcloud.cos.model.Permission.Read);assertFalse(CosAclIT.ownerOnly(acl));acl.revokeAllPermissions(com.qcloud.cos.model.GroupGrantee.AllUsers);
        com.qcloud.cos.model.Grantee authenticated=new com.qcloud.cos.model.Grantee(){private String id="http://cam.qcloud.com/groups/global/AuthenticatedUsers";public String getTypeIdentifier(){return "Group";}public String getIdentifier(){return id;}public void setIdentifier(String value){id=value;}};
        acl.grantPermission(authenticated,com.qcloud.cos.model.Permission.Read);assertFalse(CosAclIT.ownerOnly(acl));acl.revokeAllPermissions(authenticated);
        acl.grantPermission(new com.qcloud.cos.model.UinGrantee("another-account"),com.qcloud.cos.model.Permission.Read);assertFalse(CosAclIT.ownerOnly(acl));assertFalse(CosAclIT.ownerOnly(null));
    }
    @Test void shutdownClosesSdkResources(){provider.destroy();verify(client).shutdown();verifyNoMoreInteractions(client);}
}
