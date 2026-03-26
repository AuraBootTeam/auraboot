package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;
import com.auraboot.framework.meta.template.dto.TemplateGenerationResult;
import com.auraboot.framework.meta.template.service.TemplateGeneratorService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CRUD Template Generator Integration Test
 * 
 * Tests the complete flow of CRUD template generation
 * 
 * @author AuraBoot
 */
@Slf4j
public class CrudTemplateGeneratorIntegrationTest extends BaseIntegrationTest {
    
    @Autowired
    private TemplateGeneratorService templateGeneratorService;
    
    @Autowired
    private MetaModelService metaModelService;
    
    @Autowired
    private MetaFieldService metaFieldService;

    @BeforeEach
    void setUp() {
        super.setupTenantContext();
    }
    
    @Test
    public void testGenerateCrudTemplate_Success() {
        log.info("Starting CRUD template generation test");
        
        // 1. Create test model
        MetaModelDTO model = createTestModel();
        assertNotNull(model);
        assertNotNull(model.getId());
        log.info("Created test model: {}", model.getCode());
        
        // 2. Create test fields
        MetaFieldDTO field1 = createTestField("name", "string");
        MetaFieldDTO field2 = createTestField("description", "text");
        assertNotNull(field1);
        assertNotNull(field2);
        log.info("Created test fields");
        
        // 3. Bind fields to model
        bindFieldToModel(model.getId(), field1.getId(), 1);
        bindFieldToModel(model.getId(), field2.getId(), 2);
        log.info("Bound fields to model");
        
        // 4. Configure template generation
        CrudTemplateConfig config = new CrudTemplateConfig();
        config.setMenuName("Test Product");
        config.setMenuIcon("CubeIcon");
        config.setGenerateList(true);
        config.setGenerateForm(true);
        config.setGenerateDetail(true);
        config.setEnableExport(true);
        config.setEnableImport(false);
        
        // 5. Generate CRUD template
        TemplateGenerationResult result = templateGeneratorService.generateCrudPages(
            model.getCode(), config);
        
        // 6. Verify result
        assertNotNull(result);
        assertNotNull(result.getModelCode());
        assertEquals(model.getCode(), result.getModelCode());
        
        assertNotNull(result.getGeneratedResources());
        assertNotNull(result.getGeneratedResources().getPages());
        assertNotNull(result.getGeneratedResources().getMenus());
        assertNotNull(result.getGeneratedResources().getPermissions());
        
        // Verify pages generated
        assertTrue(result.getGeneratedResources().getPages().size() >= 3, 
            "Should generate at least 3 pages (list, form, detail)");
        
        // Verify menus generated
        assertTrue(result.getGeneratedResources().getMenus().size() >= 1,
            "Should generate at least 1 menu");
        
        // Verify permissions generated
        assertTrue(result.getGeneratedResources().getPermissions().size() >= 5,
            "Should generate at least 5 permissions (read, create, update, delete, export)");
        
        // Verify access links
        assertNotNull(result.getAccessLinks());
        assertNotNull(result.getAccessLinks().getListPage());
        assertNotNull(result.getAccessLinks().getFormPage());
        assertNotNull(result.getAccessLinks().getDetailPage());
        
        log.info("CRUD template generation test completed successfully");
        log.info("Generated {} pages, {} menus, {} permissions",
            result.getGeneratedResources().getPages().size(),
            result.getGeneratedResources().getMenus().size(),
            result.getGeneratedResources().getPermissions().size());
    }
    
    private MetaModelDTO createTestModel() {
        String uniqueCode = "test_crud_model_" + UniqueIdGenerator.generate().substring(0, 8).toLowerCase();
        
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(uniqueCode);
        request.setDisplayName("Test CRUD Model");
        request.setDescription("Model for CRUD template generation test");
        request.setModelType("entity");
        
        return metaModelService.create(request);
    }
    
    private MetaFieldDTO createTestField(String baseName, String dataType) {
        String uniqueCode = baseName + "_" + UniqueIdGenerator.generate().substring(0, 8).toLowerCase();
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(uniqueCode);
        request.setDataType(dataType);
        
        return metaFieldService.create(request);
    }
    
    private void bindFieldToModel(Long modelId, Long fieldId, Integer order) {
        metaModelService.bindFieldToModel(
            modelId, fieldId, order, 
            false,  // required
            true,   // visible
            true,   // editable
            null,   // defaultValue
            null,   // validationRules
            null,   // displayConfig
            null    // remarks
        );
    }
}
