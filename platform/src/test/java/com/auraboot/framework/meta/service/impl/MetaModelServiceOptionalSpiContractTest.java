package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class MetaModelServiceOptionalSpiContractTest {

    @Test
    @DisplayName("OSS model publishing resolves the optional MONEY SPI without a lazy missing-bean proxy")
    void moneyFieldExpansionUsesAnOptionalProvider() throws Exception {
        Field field = MetaModelServiceImpl.class.getDeclaredField("moneyFieldTypeHandlerProvider");

        assertThat(field.getType()).isEqualTo(ObjectProvider.class);
        assertThat(field.getAnnotation(Lazy.class)).isNull();
    }
}
