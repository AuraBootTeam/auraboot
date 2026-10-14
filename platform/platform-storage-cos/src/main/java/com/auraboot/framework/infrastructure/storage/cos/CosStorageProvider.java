package com.auraboot.framework.infrastructure.storage.cos;

import com.auraboot.framework.file.constant.StorageType;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.infrastructure.storage.StorageProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.region.Region;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Optional Tencent COS adapter; authorization remains with the calling business service. */
@Component
@ConditionalOnProperty(name="aura.storage.type",havingValue="cos")
public class CosStorageProvider implements StorageProvider,DisposableBean {
    private final COSClient client;
    private final String bucket;
    @Autowired public CosStorageProvider(StorageProperties properties) {
        var config=properties.getCos();
        bucket=required(config.getBucket(),"bucket");
        String region=required(config.getRegion(),"region");
        String id=required(config.getSecretId(),"secret-id"),secret=required(config.getSecretKey(),"secret-key");
        client=new COSClient(new BasicCOSCredentials(id,secret),httpsConfig(region));
        // Never create, probe or change a configured bucket/ACL during application startup.
    }
    CosStorageProvider(COSClient client,String bucket) {this.client=client;this.bucket=required(bucket,"bucket");}
    static ClientConfig httpsConfig(String region) {var config=new ClientConfig(new Region(region));config.setHttpProtocol(HttpProtocol.https);return config;}
    private static String required(String value,String name) {if(value==null||value.isBlank())throw new IllegalArgumentException("aura.storage.cos."+name+" is required");return value.trim();}
    private static String key(String value) {if(value==null||value.isBlank())throw new IllegalArgumentException("Object key is required");return value;}
    @Override public StorageType type() {return StorageType.COS;}
    @Override public String upload(String key,InputStream input,long size,String contentType) {
        key(key);if(input==null||size<0)throw new IllegalArgumentException("Invalid upload stream or size");
        var metadata=new ObjectMetadata();metadata.setContentLength(size);metadata.setContentType(contentType);
        client.putObject(bucket,key,input,metadata);return "cos://"+bucket+"/"+key;
    }
    @Override public InputStream download(String key) {return client.getObject(bucket,key(key)).getObjectContent();}
    @Override public void delete(String key) {client.deleteObject(bucket,key(key));}
    @Override public boolean exists(String key) {return client.doesObjectExist(bucket,key(key));}
    @Override public String getPresignedUrl(String key,Duration expiry) {
        if(expiry==null||expiry.isZero()||expiry.isNegative()||expiry.compareTo(Duration.ofHours(1))>0)throw new IllegalArgumentException("Download expiry must be positive and at most one hour");
        var request=new GeneratePresignedUrlRequest(bucket,key(key),HttpMethodName.GET);
        request.setExpiration(Date.from(Instant.now().plus(expiry)));
        var url=client.generatePresignedUrl(request);
        if(url==null||!"https".equalsIgnoreCase(url.getProtocol()))throw new IllegalStateException("COS download signature must use HTTPS");
        // Do not log URL or credentials; SDK signs the host using its default secure policy.
        return url.toExternalForm();
    }
    @Override public void destroy() {client.shutdown();}
}
