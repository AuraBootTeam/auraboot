package com.auraboot.framework.menu.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class NavigationTargetValidationTest {
    @ParameterizedTest
    @CsvSource({"external,https://example.com/docs?tab=guide", "embedded,http://localhost:8000/guide", "internal,/p/qo_quote_common?status=draft", "page,qo_quote_common_list"})
    void acceptsSupportedNavigationTargets(String kind,String target) {
        assertDoesNotThrow(() -> NavigationConfigurationService.validateTarget(kind,target));
    }
    @ParameterizedTest
    @CsvSource({"external,javascript:alert(1)","external,//example.com/docs","embedded,data:text/html;test","external,https://user:secret@example.com","internal,//example.com","internal,/a/../api","internal,https://example.com","page,../other"})
    void rejectsTargetsThatChangeTheDeclaredNavigationBoundary(String kind,String target) {
        assertThrows(RuntimeException.class,() -> NavigationConfigurationService.validateTarget(kind,target));
    }
}
