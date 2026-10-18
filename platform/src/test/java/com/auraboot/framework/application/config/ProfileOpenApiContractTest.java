package com.auraboot.framework.application.config;

import com.auraboot.framework.application.annotation.CurrentMemberId;
import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.user.controller.UserProfileController;
import com.auraboot.framework.user.dto.UserProfileResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileOpenApiContractTest {
    @Test
    void injectedIdentitiesAreHiddenWithoutHidingRealQueryParameters() throws Exception {
        new OpenApiConfig();
        var method = Parameters.class.getDeclaredMethod("read", Long.class, Long.class, Long.class);
        assertThat(SpringDocAnnotationsUtils.isAnnotationToIgnore(new MethodParameter(method, 0))).isTrue();
        assertThat(SpringDocAnnotationsUtils.isAnnotationToIgnore(new MethodParameter(method, 1))).isTrue();
        assertThat(SpringDocAnnotationsUtils.isAnnotationToIgnore(new MethodParameter(method, 2))).isFalse();
    }

    @Test
    void avatarUploadDeclaresActualMultipartMedia() throws Exception {
        var method = UserProfileController.class.getMethod("uploadAvatar", MultipartFile.class, Long.class);
        assertThat(method.getAnnotation(PostMapping.class).consumes())
                .containsExactly(MediaType.MULTIPART_FORM_DATA_VALUE);
        assertThat(method.getAnnotation(Operation.class).requestBody().required()).isTrue();
    }

    @Test
    void nullableProfileAndUnpopulatedUploadMetadataIncludeJsonNull() {
        var converters = new ModelConverters(true);
        var profile = Json31.mapper().valueToTree(converters.readAll(UserProfileResponse.class).get("UserProfileResponse"));
        for (var field : new String[]{"userName", "nickName", "email", "mobile", "area", "signature", "imgId", "avatarUrl", "lastSignInAt", "createdAt"}) {
            assertThat(profile.at("/properties/" + field + "/type").toString()).contains("null");
        }
        var upload = Json31.mapper().valueToTree(converters.readAll(FileUploadResponseDTO.class).get("FileUploadResponseDTO"));
        for (var field : new String[]{"fileName", "mimeType", "cloudPath", "createdBy"}) {
            assertThat(upload.at("/properties/" + field + "/type").toString()).contains("null");
        }
    }

    @Test
    void memberSearchSchemaIncludesTheEmailOnlyUsernameNull() {
        var schemas = new ModelConverters(true).readAll(
                com.auraboot.framework.tenant.dto.MemberSearchOption.class);
        var identity = Json31.mapper().valueToTree(schemas.get("UserIdentity"));
        assertThat(identity.at("/properties/username/type").toString()).contains("null");
        assertThat(identity.at("/properties/pid/type").toString()).doesNotContain("null");
    }

    static class Parameters {
        void read(@CurrentUserId Long caller, @CurrentMemberId Long member, @RequestParam Long userId) {}
    }
}
