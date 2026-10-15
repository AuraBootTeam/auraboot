package com.auraboot.framework.infrastructure.storage.cos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.AccessControlList;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.GroupGrantee;
import com.qcloud.cos.model.Permission;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in real GET Bucket ACL only. No object/bucket write, no raw exception/URL/credential output. */
class CosAclIT {
    static boolean ownerOnly(AccessControlList acl) {
        if(acl==null||acl.getOwner()==null||acl.getOwner().getId()==null||acl.getOwner().getId().isBlank())return false;
        if(acl.getCannedAccessControl()!=CannedAccessControlList.Private)return false;
        String owner=acl.getOwner().getId();boolean fullControl=false;
        for(var grant:acl.getGrantsAsList()) {
            if(grant.getGrantee()==null||grant.getGrantee() instanceof GroupGrantee||!owner.equals(grant.getGrantee().getIdentifier()))return false;
            if(grant.getPermission()==Permission.FullControl)fullControl=true;
        }
        return fullControl;
    }
    @Test void explicitlyAuthorizedBucketAclIsPrivateOwnerOnly()throws Exception {
        Path input=Path.of(System.getProperty("cos.acl.private.file")),out=Path.of(System.getProperty("cos.acl.receipt"));
        COSClient sdk=null;boolean verified=false;String category="CONFIG_INVALID";int status=0;
        try {
            var permissions=Files.getPosixFilePermissions(input);
            if(Files.isSymbolicLink(input)||permissions.stream().anyMatch(p->p!=PosixFilePermission.OWNER_READ&&p!=PosixFilePermission.OWNER_WRITE))throw new IllegalArgumentException();
            var json=new ObjectMapper().readTree(Files.readString(input));
            if(!"cos".equals(json.path("type").asText())||!"edu-1321667056".equals(json.path("bucket").asText())||!"ap-nanjing".equals(json.path("region").asText()))throw new IllegalArgumentException();
            String id=json.path("secretId").asText(),key=json.path("secretKey").asText();if(id.isBlank()||key.isBlank())throw new IllegalArgumentException();
            var config=CosStorageProvider.httpsConfig("ap-nanjing");config.setConnectionTimeout(5000);config.setSocketTimeout(10000);
            sdk=new COSClient(new BasicCOSCredentials(id,key),config);
            verified=ownerOnly(sdk.getBucketAcl("edu-1321667056"));status=200;category=verified?"PRIVATE_OWNER_ONLY":"ACL_NOT_OWNER_PRIVATE";
        } catch(CosServiceException failure) {status=failure.getStatusCode();category=status==403?"ACL_PERMISSION_DENIED":status==401?"AUTHENTICATION_DENIED":"COS_SERVICE_ERROR";}
          catch(IllegalArgumentException failure) {category="CONFIG_INVALID";}
          catch(Exception failure) {category="CLIENT_OR_NETWORK_ERROR";}
        finally {if(sdk!=null)sdk.shutdown();}
        Files.createDirectories(out.toAbsolutePath().getParent());new ObjectMapper().writeValue(out.toFile(),Map.of("operation","GET_BUCKET_ACL","privateOwnerOnly",verified,"statusCode",status,"category",category,"writes",0));
        assertTrue(verified,"COS ACL is not verified private; inspect the sanitized receipt category");
    }
}
