package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.notification.mapper.NotificationTemplateMapper;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The reviewed action must match execution: preserve existing and retired tenant roles. */
class PluginRoleUpgradePreviewTest {
    final PluginResourceImporter resources=mock(PluginResourceImporter.class);
    final PluginImportChangePreview previews=new PluginImportChangePreview(
            mock(PluginResourceMapper.class),resources,mock(SavedViewMapper.class),
            mock(NotificationTemplateMapper.class),(a,b)->false);
    @BeforeEach void context(){MetaContext.setContext(7L,8L,"preview-user","preview@example.test");}
    @AfterEach void clear(){MetaContext.clear();}
    ImportPreviewResult preview(){
        var manifest=new PluginManifestExtended();
        manifest.setRoles(List.of(RoleDefinitionDTO.builder().code("quote_reader").name("New template name")
                .defaultDataScopeType("all").permissions(List.of("quote.read","quote.delete")).build()));
        var result=new ImportPreviewResult();previews.generateChangePreview(manifest,result,null);return result;
    }
    @Test void existingRoleIsPreviewedAsPreservedInsteadOfUpdated(){
        when(resources.checkRoleExists(7L,"quote_reader")).thenReturn(true);
        var result=preview();
        assertThat(result.getChanges().get("ROLE").getFirst().getAction()).isEqualTo(ResourceAction.SKIP);
        assertThat(result.getActionCounts()).containsEntry("SKIP",1).doesNotContainKeys("CREATE","UPDATE");
    }
    @Test void retiredRoleIsPreviewedAsPreservedInsteadOfRecreated(){
        when(resources.checkRetiredRoleExists(7L,"quote_reader")).thenReturn(true);
        assertThat(preview().getChanges().get("ROLE").getFirst().getAction()).isEqualTo(ResourceAction.SKIP);
    }
    @Test void genuinelyNewRoleStillPreviewsInitialCreation(){
        assertThat(preview().getChanges().get("ROLE").getFirst().getAction()).isEqualTo(ResourceAction.CREATE);
    }
}
