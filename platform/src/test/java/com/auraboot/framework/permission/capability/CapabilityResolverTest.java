package com.auraboot.framework.permission.capability;

import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityResolverTest {

    private final CapabilityResolver resolver = new CapabilityResolver();

    private CapabilityGroup group(List<CapabilityGroup> groups, String name) {
        return groups.stream().filter(g -> name.equals(g.getGroup())).findFirst().orElseThrow();
    }

    private Capability cap(CapabilityGroup g, String code) {
        return g.getCapabilities().stream().filter(c -> code.equals(c.getCode())).findFirst().orElseThrow();
    }

    @Test
    void declaredCapabilityIsGrantedWhenAllIncludesHeld() {
        CapabilityDefinitionDTO decl = CapabilityDefinitionDTO.builder()
                .code("crm.cap.account").group("客户管理").nameZhCN("维护客户资料").tier("editor")
                .includes(List.of("crm.account.read", "crm.account.manage")).build();

        List<CapabilityGroup> groups = resolver.resolve(List.of(decl),
                List.of("crm.account.read", "crm.account.manage"),
                Set.of("crm.account.read", "crm.account.manage"));

        Capability c = cap(group(groups, "客户管理"), "crm.cap.account");
        assertThat(c.getLabel()).isEqualTo("维护客户资料");
        assertThat(c.getTier()).isEqualTo("editor");
        assertThat(c.isGranted()).isTrue();
        assertThat(c.isConventionDerived()).isFalse();
    }

    @Test
    void declaredCapabilityIsNotGrantedWhenAnIncludeIsMissing() {
        CapabilityDefinitionDTO decl = CapabilityDefinitionDTO.builder()
                .code("crm.cap.account").group("客户管理")
                .includes(List.of("crm.account.read", "crm.account.manage")).build();

        List<CapabilityGroup> groups = resolver.resolve(List.of(decl),
                List.of("crm.account.read", "crm.account.manage"),
                Set.of("crm.account.read")); // manage missing

        assertThat(cap(group(groups, "客户管理"), "crm.cap.account").isGranted()).isFalse();
    }

    @Test
    void sensitiveFlagPropagatesToResolvedCapability() {
        CapabilityDefinitionDTO decl = CapabilityDefinitionDTO.builder()
                .code("crm.cap.account_contact_full").group("客户管理").sensitive(true)
                .includes(List.of("crm.account.contact_unmask")).build();

        List<CapabilityGroup> groups = resolver.resolve(List.of(decl),
                List.of("crm.account.contact_unmask"), Set.of());

        assertThat(cap(group(groups, "客户管理"), "crm.cap.account_contact_full").isSensitive()).isTrue();
    }

    @Test
    void conventionDerivesUncoveredCodesByModuleThenResource() {
        List<CapabilityGroup> groups = resolver.resolve(List.of(),
                List.of("crm.lead.read", "crm.lead.manage"),
                Set.of("crm.lead.read")); // manage missing

        Capability lead = cap(group(groups, "crm"), "crm.lead");
        assertThat(lead.getIncludes()).containsExactlyInAnyOrder("crm.lead.read", "crm.lead.manage");
        assertThat(lead.isConventionDerived()).isTrue();
        assertThat(lead.isGranted()).isFalse();
    }

    @Test
    void declaredIncludesAreNotAlsoConventionDerived() {
        CapabilityDefinitionDTO decl = CapabilityDefinitionDTO.builder()
                .code("crm.cap.account").group("客户管理")
                .includes(List.of("crm.account.read", "crm.account.manage")).build();

        List<CapabilityGroup> groups = resolver.resolve(List.of(decl),
                List.of("crm.account.read", "crm.account.manage", "crm.lead.read"), Set.of());

        // crm.account.* covered by the declaration -> no convention "crm.account" capability
        boolean accountDerived = groups.stream()
                .flatMap(g -> g.getCapabilities().stream())
                .anyMatch(c -> c.isConventionDerived() && c.getCode().equals("crm.account"));
        assertThat(accountDerived).isFalse();
        // crm.lead.read is uncovered -> convention-derived under "crm"
        assertThat(cap(group(groups, "crm"), "crm.lead").getIncludes()).containsExactly("crm.lead.read");
    }

    @Test
    void conventionLabelPrefersManageActionName() {
        // sys.webhook.* — bundle label should be the manage action's full localized name, not a
        // fragile common-substring of mixed-case names (which produced "ebhook").
        Map<String, String> names = Map.of(
                "sys.webhook.manage", "Webhook管理",
                "sys.webhook.update", "System webhook update");

        List<CapabilityGroup> groups = resolver.resolve(List.of(),
                List.of("sys.webhook.manage", "sys.webhook.update"), Set.of(), names);

        Capability webhook = cap(group(groups, "sys"), "sys.webhook");
        assertThat(webhook.getLabel()).isEqualTo("Webhook管理");
        assertThat(webhook.isConventionDerived()).isTrue();
    }

    @Test
    void conventionLabelFallsBackToReadWhenNoManage() {
        // No manage/admin -> read action's name wins (next in priority).
        Map<String, String> names = Map.of(
                "billing.license.read", "查看许可证",
                "billing.license.create", "新增许可证",
                "billing.license.delete", "删除许可证");

        List<CapabilityGroup> groups = resolver.resolve(List.of(),
                List.of("billing.license.read", "billing.license.create", "billing.license.delete"),
                Set.of(), names);

        assertThat(cap(group(groups, "billing"), "billing.license").getLabel()).isEqualTo("查看许可证");
    }

    @Test
    void conventionLabelFallsBackToRawResourceWhenNoNames() {
        // 3-arg overload (no names) preserves the original raw-resource label behaviour.
        List<CapabilityGroup> groups = resolver.resolve(List.of(),
                List.of("crm.lead.read", "crm.lead.manage"), Set.of());

        assertThat(cap(group(groups, "crm"), "crm.lead").getLabel()).isEqualTo("lead");
    }

    @Test
    void declaredCapabilityLabelUnaffectedByPermissionNames() {
        CapabilityDefinitionDTO decl = CapabilityDefinitionDTO.builder()
                .code("crm.cap.account").group("客户管理").nameZhCN("维护客户资料")
                .includes(List.of("crm.account.read")).build();

        List<CapabilityGroup> groups = resolver.resolve(List.of(decl),
                List.of("crm.account.read"), Set.of(), Map.of("crm.account.read", "查看客户"));

        assertThat(cap(group(groups, "客户管理"), "crm.cap.account").getLabel()).isEqualTo("维护客户资料");
    }

    @Test
    void declaredCapabilityInheritsDisplayMetadataFromIncludedPermission() {
        CapabilityDefinitionDTO quoteView = CapabilityDefinitionDTO.builder()
                .code("qo.cap.quote_view").group("报价单").nameZhCN("查看报价")
                .includes(List.of("qo.quote.read")).order(99).build();
        CapabilityDefinitionDTO ruleManage = CapabilityDefinitionDTO.builder()
                .code("bom.cap.rule_manage").group("规则").nameZhCN("编辑规则")
                .includes(List.of("bom.rule.manage")).order(1).build();

        List<CapabilityGroup> groups = resolver.resolve(
                List.of(ruleManage, quoteView),
                List.of("qo.quote.read", "bom.rule.manage"),
                Set.of(),
                Map.of(),
                Map.of(
                        "qo.quote.read", Map.of("displayGroup", "报价管理", "displayGroupOrder", 10, "displayOrder", 10),
                        "bom.rule.manage", Map.of("displayGroup", "规则配置", "displayGroupOrder", 80, "displayOrder", 20, "sensitive", true)
                ));

        assertThat(groups).extracting(CapabilityGroup::getGroup).containsExactly("报价管理", "规则配置");
        Capability quote = cap(group(groups, "报价管理"), "qo.cap.quote_view");
        assertThat(quote.getDisplayGroupOrder()).isEqualTo(10);
        assertThat(quote.getDisplayOrder()).isEqualTo(10);
        Capability rule = cap(group(groups, "规则配置"), "bom.cap.rule_manage");
        assertThat(rule.isSensitive()).isTrue();
    }

    @Test
    void expandToPermissionCodesUnionsSelectedIncludes() {
        CapabilityDefinitionDTO account = CapabilityDefinitionDTO.builder()
                .code("crm.cap.account").includes(List.of("crm.account.read", "crm.account.manage")).build();
        CapabilityDefinitionDTO lead = CapabilityDefinitionDTO.builder()
                .code("crm.cap.lead").includes(List.of("crm.lead.read")).build();

        Set<String> codes = resolver.expandToPermissionCodes(
                Set.of("crm.cap.account", "crm.cap.lead"), List.of(account, lead));

        assertThat(codes).containsExactlyInAnyOrder(
                "crm.account.read", "crm.account.manage", "crm.lead.read");
    }
}
