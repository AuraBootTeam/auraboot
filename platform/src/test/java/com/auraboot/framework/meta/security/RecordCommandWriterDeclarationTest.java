package com.auraboot.framework.meta.security;

import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RecordCommandWriterDeclarationTest {
    @Test void malformedDeclarationsAreRejectedBeforeExtensionPersistence() {
        for(Object raw:List.of(true,Map.of("field","managed"),
                Map.of("field","managed","commands",Map.of("create",List.of(),"update",List.of())),
                Map.of("field","managed","commands",Map.of("create",List.of(" "),"update",List.of(),"delete",List.of())),
                Map.of("field","managed","commands",Map.of("create",List.of(" app:create"),"update",List.of(),"delete",List.of())))) {
            var bean=new ExtensionBean();bean.setExtension(Map.of("recordCommandWriters",raw));
            assertThatThrownBy(bean::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recordCommandWriters");
        }
    }
    @Test void validFlatAndNestedDeclarationsRetainEmptyDenyLists() {
        var declaration=Map.of("field","managed","commands",Map.of("create",List.of("app:create"),"update",List.of("app:save"),"delete",List.of()));
        var nested=new ExtensionBean();nested.setExtension(Map.of("recordCommandWriters",declaration));
        var flat=new ExtensionBean();flat.setDynamicProperty("recordCommandWriters",declaration);
        assertThatCode(nested::validate).doesNotThrowAnyException();
        assertThatCode(flat::validate).doesNotThrowAnyException();
        assertThatCode(new ExtensionBean()::validate).doesNotThrowAnyException();
        assertThat(nested.get("recordCommandWriters")).isEqualTo(declaration);
    }
}
