package com.auraboot.framework.plugin.marketplace;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.marketplace.dto.*;
import com.auraboot.framework.plugin.marketplace.entity.MarketplaceSolution;
import com.auraboot.framework.plugin.marketplace.entity.MarketplaceSolutionInstall;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplaceSolutionInstallMapper;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplaceSolutionMapper;
import com.auraboot.framework.plugin.marketplace.service.SolutionAdminService;
import com.auraboot.framework.plugin.marketplace.service.SolutionBrowseService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SolutionServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SolutionAdminService adminService;

    @Autowired
    private SolutionBrowseService browseService;

    @Autowired
    private MarketplaceSolutionMapper solutionMapper;

    @Autowired
    private MarketplaceSolutionInstallMapper installMapper;

    private String testCode;

    @BeforeEach
    void setupTestData() {
        testCode = "test-sol-" + System.currentTimeMillis();
    }

    @Test
    @Order(1)
    void shouldCreateSolution() {
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("Test Solution");
        req.setNameZh("测试方案");
        req.setNameEn("Test Solution");
        req.setDescription("A test solution");
        req.setIndustry("general");
        req.setPluginCodes(List.of("crm", "sales"));
        req.setPriceType("free");
        req.setTags(List.of("test", "crm"));

        MarketplaceSolution created = adminService.create(req);

        assertThat(created).isNotNull();
        assertThat(created.getPid()).isNotBlank();
        assertThat(created.getCode()).isEqualTo(testCode);
        assertThat(created.getName()).isEqualTo("Test Solution");
        assertThat(created.getStatus()).isEqualTo("draft");
        assertThat(created.getIndustry()).isEqualTo("general");
    }

    @Test
    @Order(2)
    void shouldRejectDuplicateCode() {
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("First");
        req.setPluginCodes(List.of("crm"));
        adminService.create(req);

        SolutionCreateRequest req2 = new SolutionCreateRequest();
        req2.setCode(testCode);
        req2.setName("Second");
        req2.setPluginCodes(List.of("sales"));

        assertThatThrownBy(() -> adminService.create(req2))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    @Order(3)
    void shouldUpdateSolution() {
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("Original");
        req.setPluginCodes(List.of("crm"));
        adminService.create(req);

        SolutionCreateRequest update = new SolutionCreateRequest();
        update.setName("Updated Name");
        update.setDescription("Updated description");
        update.setPluginCodes(List.of("crm", "sales", "inventory"));

        MarketplaceSolution updated = adminService.update(testCode, update);

        assertThat(updated.getName()).isEqualTo("Updated Name");
        assertThat(updated.getDescription()).isEqualTo("Updated description");
        assertThat(updated.getPluginCodes()).contains("inventory");
    }

    @Test
    @Order(4)
    void shouldPublishAndArchiveSolution() {
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("Publishable");
        req.setPluginCodes(List.of("crm"));
        adminService.create(req);

        adminService.publish(testCode);
        MarketplaceSolution published = solutionMapper.findByCode(testCode);
        assertThat(published.getStatus()).isEqualTo("published");
        assertThat(published.getPublishedAt()).isNotNull();

        adminService.archive(testCode);
        MarketplaceSolution archived = solutionMapper.findByCode(testCode);
        assertThat(archived.getStatus()).isEqualTo("archived");
    }

    @Test
    @Order(5)
    void shouldDeleteSolution() {
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("Deletable");
        req.setPluginCodes(List.of("crm"));
        adminService.create(req);

        adminService.delete(testCode);
        MarketplaceSolution deleted = solutionMapper.findByCode(testCode);
        assertThat(deleted).isNull(); // soft-deleted, not visible
    }

    @Test
    @Order(6)
    void shouldBrowsePublishedSolutions() {
        // Create and publish a solution
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("Browsable Solution");
        req.setIndustry("manufacturing");
        req.setPluginCodes(List.of("crm", "sales"));
        adminService.create(req);
        adminService.publish(testCode);

        // Search all published
        List<SolutionDTO> all = browseService.search(null, null, "popular");
        assertThat(all).isNotEmpty();

        // Search by industry
        List<SolutionDTO> mfg = browseService.search(null, "manufacturing", "popular");
        boolean found = mfg.stream().anyMatch(s -> s.getCode().equals(testCode));
        assertThat(found).isTrue();

        // Search by keyword
        List<SolutionDTO> searched = browseService.search("Browsable", null, "popular");
        assertThat(searched).anyMatch(s -> s.getCode().equals(testCode));
    }

    @Test
    @Order(7)
    void shouldGetSolutionDetail() {
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("Detail Test");
        req.setIndustry("general");
        req.setPluginCodes(List.of("crm", "sales"));
        req.setReadmeMarkdown("# Test\nSome docs");
        req.setTags(List.of("test"));
        adminService.create(req);
        adminService.publish(testCode);

        SolutionDetailDTO detail = browseService.getDetail(testCode);

        assertThat(detail).isNotNull();
        assertThat(detail.getCode()).isEqualTo(testCode);
        assertThat(detail.getPluginCodes()).hasSize(2);
        assertThat(detail.getPlugins()).hasSize(2);
        assertThat(detail.getReadmeMarkdown()).contains("# Test");
        assertThat(detail.getTags()).contains("test");
    }

    @Test
    @Order(8)
    void shouldGetDetailForNonexistentSolution() {
        assertThatThrownBy(() -> browseService.getDetail("nonexistent-code"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("not found");
    }

    @Test
    @Order(9)
    void shouldTrackInstallation() {
        // Create and publish
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("Install Track");
        req.setPluginCodes(List.of("crm"));
        adminService.create(req);
        adminService.publish(testCode);

        MarketplaceSolution sol = solutionMapper.findByCode(testCode);

        // Manually insert install record (bypassing actual plugin install)
        MarketplaceSolutionInstall install = MarketplaceSolutionInstall.builder()
            .pid(UlidGenerator.nextULID())
            .tenantId(getTestTenant().getId())
            .solutionPid(sol.getPid())
            .installedPluginPids("[]")
            .installedAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        installMapper.insert(install);

        // Verify installed status
        List<SolutionDTO> installed = browseService.getInstalled();
        assertThat(installed).anyMatch(s -> s.getCode().equals(testCode));

        // Verify detail shows installed
        SolutionDetailDTO detail = browseService.getDetail(testCode);
        assertThat(detail.getInstalled()).isTrue();
    }

    @Test
    @Order(10)
    void shouldListIndustries() {
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("Industry Test");
        req.setIndustry("healthcare");
        req.setPluginCodes(List.of("crm"));
        adminService.create(req);
        adminService.publish(testCode);

        List<String> industries = browseService.getIndustries();
        assertThat(industries).contains("healthcare");
    }

    @Test
    @Order(11)
    void shouldListAllForAdmin() {
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("Admin List Test");
        req.setPluginCodes(List.of("crm"));
        adminService.create(req);

        List<MarketplaceSolution> all = adminService.listAll();
        assertThat(all).anyMatch(s -> s.getCode().equals(testCode));
        // Should include DRAFT solutions
        MarketplaceSolution draft = all.stream()
            .filter(s -> s.getCode().equals(testCode)).findFirst().orElse(null);
        assertThat(draft).isNotNull();
        assertThat(draft.getStatus()).isEqualTo("draft");
    }

    @Test
    @Order(12)
    void shouldGetFeaturedSolutions() {
        // Create and publish a featured solution
        SolutionCreateRequest req = new SolutionCreateRequest();
        req.setCode(testCode);
        req.setName("Featured Test");
        req.setPluginCodes(List.of("crm"));
        MarketplaceSolution created = adminService.create(req);

        // Manually set featured
        created.setFeatured(true);
        solutionMapper.updateById(created);
        adminService.publish(testCode);

        List<SolutionDTO> featured = browseService.getFeatured();
        assertThat(featured).anyMatch(s -> s.getCode().equals(testCode));
    }
}
