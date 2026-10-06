package com.auraboot.framework.meta.service.impl;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** History comparison and statistics include unpublished and unknown metadata accurately. */
class ModelHistoryQuerySupportTest {
    final MetaModelMapper mapper=mock(MetaModelMapper.class);
    final ModelHistoryQuerySupport.IsCodeUniqueOperation unique=mock(ModelHistoryQuerySupport.IsCodeUniqueOperation.class);
    final ModelHistoryQuerySupport support=new ModelHistoryQuerySupport(mapper,unique,m -> { return MetaModelDTO.builder().pid(m.getPid()).build(); },(m,n) -> MetaModelDTO.builder().build(),models -> Map.of());
    @Test void versionHistoryRetainsOrderAndMissingDetailFailsExplicitly() {
        var first=new Model(); first.setPid("v1"); var second=new Model(); second.setPid("v2");
        when(mapper.findAllVersionsByCode("invoice")).thenReturn(List.of(second,first));
        when(mapper.findByCodeAndVersion("invoice",1)).thenReturn(first);
        assertThat(support.getVersionHistory("invoice")).extracting(MetaModelDTO::getPid).containsExactly("v2","v1");
        assertThat(support.getVersionDetail("invoice",1).getPid()).isEqualTo("v1");
        assertThatThrownBy(() -> support.getVersionDetail("invoice",99)).isInstanceOf(MetaServiceException.class);
    }
    @Test void comparisonReportsActualChangesIncludingNullValuesAndUnchangedVersions() {
        var first=new Model(); var second=new Model(); var ext=new com.auraboot.framework.meta.entity.payload.ExtensionBean(); ext.setExtension(Map.of("displayName","Invoice","description","Billing record","modelType","view")); second.setExtension(ext); second.setStatus("published");
        when(mapper.findByCodeAndVersion("invoice",1)).thenReturn(first); when(mapper.findByCodeAndVersion("invoice",2)).thenReturn(second);
        var result=support.compareVersions("invoice",1,2);
        assertThat(result).containsEntry("hasChanges",true).containsEntry("code","invoice");
        @SuppressWarnings("unchecked") var changes=(List<Map<String,Object>>)result.get("changes");
        assertThat(changes).containsExactly(Map.of("field","displayName","oldValue","","newValue","Invoice"),Map.of("field","description","oldValue","","newValue","Billing record"),Map.of("field","modelType","oldValue","entity","newValue","view"),Map.of("field","status","oldValue","","newValue","published"));
        assertThat(support.compareVersions("invoice",2,2)).containsEntry("hasChanges",false).containsEntry("changes",List.of());
        assertThatThrownBy(() -> support.compareVersions("invoice",2,99)).isInstanceOf(MetaServiceException.class);
    }
    @Test void statisticsCountPublishedModelsAndRetainUnknownCategories() {
        var published=new Model(); published.setStatus("published"); 
        var draft=new Model(); draft.setStatus("draft"); 
        when(mapper.findCurrentByTenant()).thenReturn(List.of(published,draft,new Model()));
        assertThat(support.getStatistics()).containsEntry("totalModels",3L).containsEntry("activeModels",1L)
                .containsEntry("modelsByStatus",Map.of("published",1L,"draft",1L,"unknown",1L)).containsEntry("modelsByType",Map.of("entity",3L));
    }
    @Test void validationAggregatesErrorsAndExcludesCurrentIdentityFromUniquenessCheck() {
        when(unique.execute("invoice","m-42")).thenReturn(true);
        assertThat(support.validateModelData(Map.of("code","invoice","pid","m-42","displayName","Invoices","modelType","entity"))).containsEntry("valid",true).containsEntry("errors",Map.of());
        var invalid=support.validateModelData(Map.of("code","9 bad","displayName"," ","modelType","invalid"));
        assertThat(invalid).containsEntry("valid",false);
        assertThat((Map<?,?>)invalid.get("errors")).hasSize(3);
        assertThat(support.validateModelData(Map.of())).containsEntry("valid",false);
    }
}
