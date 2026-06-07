package com.auraboot.framework.meta.entity;

import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelNbaTest {

    @Test
    void isNbaEnabled_returnsNull_whenExtensionIsNull() {
        Model model = new Model();
        model.setExtension(null);
        assertThat(model.isNbaEnabled()).isNull();
    }

    @Test
    void isNbaEnabled_returnsNull_whenKeyNotPresent() {
        Model model = new Model();
        ExtensionBean ext = new ExtensionBean();
        ext.setDynamicProperty("icon", "Star");
        model.setExtension(ext);
        assertThat(model.isNbaEnabled()).isNull();
    }

    @Test
    void isNbaEnabled_returnsTrue_whenFlatFormat() {
        Model model = new Model();
        ExtensionBean ext = new ExtensionBean();
        ext.setDynamicProperty("enableNba", true);
        model.setExtension(ext);
        assertThat(model.isNbaEnabled()).isTrue();
    }

    @Test
    void isNbaEnabled_returnsFalse_whenExplicitlyDisabled() {
        Model model = new Model();
        ExtensionBean ext = new ExtensionBean();
        ext.setDynamicProperty("enableNba", false);
        model.setExtension(ext);
        assertThat(model.isNbaEnabled()).isFalse();
    }

    @Test
    void isNbaEnabled_returnsTrue_whenNestedFormat() {
        Model model = new Model();
        ExtensionBean ext = new ExtensionBean();
        ext.setExtension(Map.of("enableNba", true));
        model.setExtension(ext);
        assertThat(model.isNbaEnabled()).isTrue();
    }

    @Test
    void defaultPageSkipFlags_returnFalse_whenNotConfigured() {
        Model model = new Model();

        assertThat(model.isSkipDefaultPages()).isFalse();
        assertThat(model.isSkipListPageCreation()).isFalse();
        assertThat(model.isSkipFormPageCreation()).isFalse();
        assertThat(model.isSkipDetailPageCreation()).isFalse();
    }

    @Test
    void defaultPageSkipFlags_readFlatExtension() {
        Model model = new Model();
        ExtensionBean ext = new ExtensionBean();
        ext.setDynamicProperty("skipDefaultPages", "true");
        ext.setDynamicProperty("skipListPage", true);
        ext.setDynamicProperty("skipFormPage", true);
        ext.setDynamicProperty("skipDetailPage", false);
        model.setExtension(ext);

        assertThat(model.isSkipDefaultPages()).isTrue();
        assertThat(model.isSkipListPageCreation()).isTrue();
        assertThat(model.isSkipFormPageCreation()).isTrue();
        assertThat(model.isSkipDetailPageCreation()).isFalse();
    }

    @Test
    void defaultPageSkipFlags_readNestedExtension() {
        Model model = new Model();
        ExtensionBean ext = new ExtensionBean();
        ext.setExtension(Map.of(
                "skipListPage", true,
                "skipFormPage", "true",
                "skipDetailPage", true
        ));
        model.setExtension(ext);

        assertThat(model.isSkipListPageCreation()).isTrue();
        assertThat(model.isSkipFormPageCreation()).isTrue();
        assertThat(model.isSkipDetailPageCreation()).isTrue();
    }
}
