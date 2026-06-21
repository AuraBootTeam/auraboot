package com.auraboot.framework.permission.capability;

import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
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
                .code("crm.cap.account").group("客户管理").nameZhCN("维护客户资料")
                .includes(List.of("crm.account.read", "crm.account.manage")).build();

        List<CapabilityGroup> groups = resolver.resolve(List.of(decl),
                List.of("crm.account.read", "crm.account.manage"),
                Set.of("crm.account.read", "crm.account.manage"));

        Capability c = cap(group(groups, "客户管理"), "crm.cap.account");
        assertThat(c.getLabel()).isEqualTo("维护客户资料");
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
