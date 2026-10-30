package com.auraboot.framework.workbench.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;

class AnnouncementPreparationContentTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={" ","<p><br></p>","<p>&nbsp;&#8203;</p>","<!-- only a comment -->",
        "<script>alert(1)</script>","<style>body{color:red}</style>","<img src='javascript:alert(1)'>"})
    void invisibleOrUnsafeOnlyBodiesCannotSatisfyRequiredContent(String body) {
        assertThat(AnnouncementPreparationService.hasBody(body)).isFalse();
    }
    @ParameterizedTest
    @ValueSource(strings={"Plain policy","<p><strong>Rich policy</strong></p>","<img src='/policy.png'>","<img src='https://example.com/policy.png'>"})
    void visibleTextAndSafeBodyImageReferencesCanBePrepared(String body) {
        assertThat(AnnouncementPreparationService.hasBody(body)).isTrue();
    }
}
