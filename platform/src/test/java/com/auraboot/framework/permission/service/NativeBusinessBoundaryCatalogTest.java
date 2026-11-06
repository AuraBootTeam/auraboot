package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeBusinessBoundaryCatalogTest {
    private final CommandDefinitionMapper commands = mock(CommandDefinitionMapper.class);
    private final BindingRuleMapper rules = mock(BindingRuleMapper.class);
    private final ExtensionRegistry extensions = mock(ExtensionRegistry.class);
    private final NativeBusinessBoundaryCatalog catalog = new NativeBusinessBoundaryCatalog(commands, rules, extensions, new ObjectMapper());

    @BeforeEach void context() { MetaContext.setCurrentTenantId(100L); }
    @AfterEach void clear() { MetaContext.clear(); }

    private CommandDefinition command(long id, String code) {
        var command = new CommandDefinition(); command.setId(id); command.setPid("command-" + id);
        command.setTenantId(100L); command.setCode(code); command.setModelCode("bom_project");
        command.setStatus("published"); command.setIsCurrent(true); command.setDeletedFlag(false);
        command.setExecutionConfig("""
                {"type":"update","inputFields":["remark"],"permissions":["bom.project.manage"],
                 "handlerParams":{"targetRecordAuthorization":"business","targetRecordAction":"update"}}
                """);
        return command;
    }

    @Test void discoversCurrentNativeContractsAndLoadsTheirRulesInOneBatch() {
        when(commands.findCurrentNativeBusinessDefinitions()).thenReturn(List.of(command(10, "bom:update"), command(11, "bom:edit")));
        var definitions = catalog.snapshot();
        assertThat(definitions).hasSize(2);
        assertThat(definitions.getFirst().contract().permissions()).containsExactly("bom.project.manage");
        assertThat(definitions.getFirst().contract().model()).isEqualTo("bom_project");
        verify(rules).findByCommandIds(List.of(10L, 11L)); verifyNoMoreInteractions(rules);
    }

    @Test void emptyNativeCatalogDoesNotIssueAnEmptyInQuery() {
        assertThat(catalog.snapshot()).isEmpty(); verifyNoInteractions(rules);
    }

    @Test void ambiguousCurrentDefinitionsCannotProduceTwoSupposedlyExecutableBoundaries() {
        when(commands.findCurrentNativeBusinessDefinitions()).thenReturn(List.of(command(10, "bom:update"), command(11, "bom:update")));
        assertThatThrownBy(catalog::snapshot).isInstanceOf(AccessDeniedException.class); verifyNoInteractions(rules);
    }

    @Test void missingTenantOrForeignDefinitionsCannotExposeStoredMetadata() {
        MetaContext.clear(); assertThatThrownBy(catalog::snapshot).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(commands, rules);
        MetaContext.setCurrentTenantId(100L);
        var foreign = command(10, "bom:update"); foreign.setTenantId(200L);
        when(commands.findCurrentNativeBusinessDefinitions()).thenReturn(List.of(foreign));
        assertThatThrownBy(catalog::snapshot).isInstanceOf(AccessDeniedException.class); verifyNoInteractions(rules);
    }

    @Test void unexpectedUnpublishedOrRetiredDefinitionsAreRejectedDefensively() {
        var command = command(10, "bom:update"); command.setStatus("draft");
        when(commands.findCurrentNativeBusinessDefinitions()).thenReturn(List.of(command));
        assertThatThrownBy(catalog::snapshot).isInstanceOf(AccessDeniedException.class);
        command.setStatus("published"); command.setDeletedFlag(true);
        assertThatThrownBy(catalog::snapshot).isInstanceOf(AccessDeniedException.class); verifyNoInteractions(rules);
    }

    @Test void inputOrDisabledRuleChangesAreRetainedInTheDefinitionSnapshot() {
        var command = command(10, "bom:update");
        when(commands.findCurrentNativeBusinessDefinitions()).thenReturn(List.of(command));
        String before = catalog.snapshot().getFirst().source();
        command.setExecutionConfig(command.getExecutionConfig().replace("[\"remark\"]", "[\"remark\",\"name\"]"));
        assertThat(catalog.snapshot().getFirst().source()).isNotEqualTo(before);
        var rule = new BindingRule(); rule.setId(20L); rule.setCommandId(10L); rule.setTenantId(100L); rule.setEnabled(false);
        when(rules.findByCommandIds(List.of(10L))).thenReturn(List.of(rule));
        String disabled = catalog.snapshot().getFirst().source();
        rule.setExpression("changed"); assertThat(catalog.snapshot().getFirst().source()).isNotEqualTo(disabled);
        rule.setEnabled(true);
        assertThatThrownBy(catalog::snapshot).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }

    @Test void foreignRuleCannotBeSilentlyFoldedIntoALocalDefinition() {
        when(commands.findCurrentNativeBusinessDefinitions()).thenReturn(List.of(command(10, "bom:update")));
        var rule = new BindingRule(); rule.setTenantId(200L); rule.setCommandId(10L);
        when(rules.findByCommandIds(List.of(10L))).thenReturn(List.of(rule));
        assertThatThrownBy(catalog::snapshot).isInstanceOf(AccessDeniedException.class);
    }

    @Test void malformedConfigurationDoesNotDisappearFromTheMigrationDenominator() {
        var command = command(10, "bom:update"); command.setExecutionConfig("{");
        when(commands.findCurrentNativeBusinessDefinitions()).thenReturn(List.of(command));
        assertThatThrownBy(catalog::snapshot).isInstanceOf(AccessDeniedException.class);
    }
    @Test void ownerFingerprintRetainsMissingPublicationAndDefinitionChanges() {
        String missing = catalog.ownerSources(List.of("bom:download_raw"));
        assertThat(missing).contains("missingDefinitions", "bom:download_raw");
        verifyNoInteractions(rules);
        var command = command(10, "bom:download_raw");
        when(commands.findCurrentDefinitionsByCodes(List.of("bom:download_raw"))).thenReturn(List.of(command));
        String published = catalog.ownerSources(List.of("bom:download_raw"));
        assertThat(published).isNotEqualTo(missing);
        command.setStatus("draft");
        assertThat(catalog.ownerSources(List.of("bom:download_raw"))).isNotEqualTo(published);
        String withdrawn = catalog.ownerSources(List.of("bom:download_raw"));
        command.setInputSchema("{\"newField\":true}");
        assertThat(catalog.ownerSources(List.of("bom:download_raw"))).isNotEqualTo(withdrawn);
    }

    @Test void ownerFingerprintIncludesDisabledRulesAndRejectsForeignOrDuplicateRows() {
        var command = command(10, "bom:download_raw");
        when(commands.findCurrentDefinitionsByCodes(List.of("bom:download_raw"))).thenReturn(List.of(command));
        var rule = new BindingRule(); rule.setId(21L); rule.setTenantId(100L); rule.setCommandId(10L); rule.setEnabled(false);
        when(rules.findByCommandIds(List.of(10L))).thenReturn(List.of(rule));
        String before = catalog.ownerSources(List.of("bom:download_raw"));
        rule.setExpression("changed");
        assertThat(catalog.ownerSources(List.of("bom:download_raw"))).isNotEqualTo(before);
        rule.setTenantId(200L);
        assertThatThrownBy(() -> catalog.ownerSources(List.of("bom:download_raw"))).isInstanceOf(AccessDeniedException.class);
        rule.setTenantId(100L);
        when(commands.findCurrentDefinitionsByCodes(List.of("bom:download_raw"))).thenReturn(List.of(command, command));
        assertThatThrownBy(() -> catalog.ownerSources(List.of("bom:download_raw"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test void emptyOwnerSetDoesNotQueryAndForeignDefinitionsAreRejected() {
        assertThat(catalog.ownerSources(List.of())).isEmpty(); verifyNoInteractions(commands, rules);
        var command = command(10, "bom:download_raw"); command.setTenantId(200L);
        when(commands.findCurrentDefinitionsByCodes(List.of("bom:download_raw"))).thenReturn(List.of(command));
        assertThatThrownBy(() -> catalog.ownerSources(List.of("bom:download_raw"))).isInstanceOf(AccessDeniedException.class);
        MetaContext.clear();
        assertThatThrownBy(() -> catalog.ownerSources(List.of())).isInstanceOf(AccessDeniedException.class);
    }

}
