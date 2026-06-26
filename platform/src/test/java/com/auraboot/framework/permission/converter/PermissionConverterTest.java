package com.auraboot.framework.permission.converter;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.postgresql.util.PGobject;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionConverterTest {

    private final PermissionConverter converter = Mappers.getMapper(PermissionConverter.class);

    @Test
    void mapsPostgresJsonbExtensionToDtoMap() throws Exception {
        PGobject extension = new PGobject();
        extension.setType("jsonb");
        extension.setValue("{\"displayGroup\":\"报价管理\",\"displayGroupOrder\":10,\"focusedRolePermission\":true}");

        var dtoExtension = converter.objectToMap(extension);

        assertThat(dtoExtension).containsEntry("displayGroup", "报价管理");
        assertThat(dtoExtension).containsEntry("displayGroupOrder", 10);
        assertThat(dtoExtension).containsEntry("focusedRolePermission", true);
    }
}
