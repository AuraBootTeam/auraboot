package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** File import must inspect the persisted parent before any raw mapper insertion. */
class DynamicDataImportParentTest {
    @TempDir Path directory;
    final DynamicDataMapper mapper=mock(DynamicDataMapper.class);
    @AfterEach void clear() { MetaContext.clear(); org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    ImportResult run(List<Map<String,Object>> parents) throws Exception {
        MetaContext.setContext(1L,7L,null,"system");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        var parent=RecordCommandWriterParentTest.parentModel("mt_parent");
        var child=RecordCommandWriterParentTest.childModel("mt_child");
        var support=new DynamicDataTransferSupport(null,mapper,new ObjectMapper(),null,null,null,null,
            null,null,null,null,null,null,null,code -> code.equals("order") ? parent : child,code -> {},
            (model,data) -> { var columns=new HashMap<>(data);columns.put("order_pid",columns.remove("order_link"));return columns; },
            (model,sort) -> sort);
        when(mapper.selectRecordWriterTargetsForUpdate(anyString(),anyString(),anyString(),anyMap())).thenReturn(parents);
        Path file=directory.resolve("lines.json");Files.writeString(file,"[{\"order_link\":\"parent-a\"}]");
        return support.importData("line",DataImportRequest.builder().filePath(file.toString()).format(DataImportRequest.ImportFormat.JSON).build());
    }
    @Test void unmarkedImportedChildCannotAttachToManagedParent() throws Exception {
        var result=run(List.of(Map.of("managed",true)));
        assertThat(result.getSuccess()).isFalse();assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getErrors().get(0).getErrorMessage()).contains("RECORD_WRITER_DENIED");
        verify(mapper,never()).insert(anyString(),anyMap());
    }
    @Test void ordinaryParentRetainsFileImportBehavior() throws Exception {
        var result=run(List.of(Map.of("managed",false)));
        assertThat(result.getSuccess()).isTrue();assertThat(result.getSuccessCount()).isEqualTo(1);
        verify(mapper).insert(eq("mt_child"),argThat(data -> data.get("order_pid").equals("parent-a") && data.get("tenant_id").equals(1L)));
    }
    @Test void missingOrForeignParentRejectsFileImport() throws Exception {
        var result=run(List.of());assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrors().get(0).getErrorMessage()).contains("current tenant");
        verify(mapper,never()).insert(anyString(),anyMap());
    }
}
