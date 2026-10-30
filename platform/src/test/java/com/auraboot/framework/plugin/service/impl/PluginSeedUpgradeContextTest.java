package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.exception.PluginException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PluginSeedUpgradeContextTest {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @BeforeEach void context() { MetaContext.setContext(1L, 2L, "reviewer", "Reviewer"); }
    @AfterEach void clear() { TransactionSynchronizationManager.setActualTransactionActive(false); MetaContext.clear(); }

    @Test void unknownBaselinePreservesExistingDefinition() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var upgrade = new PluginSeedUpgradeContext(jdbc, new ObjectMapper(), Map.of());
        var result = new ImportExecuteResult();
        assertThat(upgrade.preserve(PluginSeedUpgradeContext.Kind.DECISION, "acceptance", true, "pid",
                ImportRequest.ConflictStrategy.OVERWRITE_SAFE, result)).isTrue();
        assertThat(result.getWarnings()).singleElement().asString().contains("unknown import baseline");
        assertThat(upgrade.baselines()).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test void baselineReadFailurePropagatesInsteadOfGrantingOverwrite() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("injected baseline read failure"));
        var upgrade = new PluginSeedUpgradeContext(jdbc, new ObjectMapper(), Map.of("DECISION:acceptance", Map.of("definition", "old")));
        assertThatThrownBy(() -> upgrade.preserve(PluginSeedUpgradeContext.Kind.DECISION, "acceptance", true, "pid",
                ImportRequest.ConflictStrategy.OVERWRITE_SAFE, new ImportExecuteResult()))
                .isInstanceOf(DataAccessResourceFailureException.class).hasMessageContaining("injected");
        assertThat(upgrade.baselines()).containsKey("DECISION:acceptance");
    }

    @Test void configurationLocksRequireAnActualImportTransaction() {
        var upgrade = new PluginSeedUpgradeContext(jdbc, new ObjectMapper(), Map.of());
        assertThatThrownBy(() -> upgrade.lock(PluginSeedUpgradeContext.Kind.AUTOMATION))
                .isInstanceOf(PluginException.class).hasMessageContaining("active import transaction");
        verifyNoInteractions(jdbc);
    }

    @Test void missingExistingIdentityCannotBeClassifiedAsNew() {
        var upgrade = new PluginSeedUpgradeContext(jdbc, new ObjectMapper(), Map.of());
        assertThatThrownBy(() -> upgrade.preserve(PluginSeedUpgradeContext.Kind.DECISION, "acceptance", true, null,
                ImportRequest.ConflictStrategy.OVERWRITE_SAFE, new ImportExecuteResult()))
                .isInstanceOf(PluginException.class).hasMessageContaining("stable resource identity");
        verifyNoInteractions(jdbc);
    }
}
