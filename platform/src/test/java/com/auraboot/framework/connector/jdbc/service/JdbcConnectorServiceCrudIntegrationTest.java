package com.auraboot.framework.connector.jdbc.service;

import com.auraboot.framework.connector.jdbc.dto.JdbcConnectorCreateRequest;
import com.auraboot.framework.connector.jdbc.dto.JdbcEndpointCreateRequest;
import com.auraboot.framework.connector.jdbc.entity.JdbcConnector;
import com.auraboot.framework.connector.jdbc.entity.JdbcConnectorEndpoint;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JdbcConnectorService — CRUD + endpoint persistence (integration)")
class JdbcConnectorServiceCrudIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcConnectorService service;

    @BeforeEach
    void setUp() {
        super.setupTenantContext();
    }

    // ==================== Helpers ====================

    private JdbcConnectorCreateRequest req() {
        JdbcConnectorCreateRequest r = new JdbcConnectorCreateRequest();
        r.setName("test-mysql");
        r.setJdbcUrl("jdbc:mysql://localhost:3306/test");
        r.setUsername("root");
        r.setPassword("secret");
        r.setMaxPoolSize(3);
        r.setConnectionTimeoutMs(5000);
        return r;
    }

    private JdbcConnector createConnector(String name) {
        JdbcConnectorCreateRequest r = req();
        r.setName(name);
        return service.create(r);
    }

    private JdbcConnectorEndpoint addEndpoint(String connectorPid, String code) {
        JdbcEndpointCreateRequest ep = new JdbcEndpointCreateRequest();
        ep.setCode(code);
        ep.setName("Test endpoint " + code);
        ep.setOperation("query");
        ep.setSqlTemplate("SELECT 1");
        return service.addEndpoint(connectorPid, ep);
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("create stores password encrypted (not plaintext)")
    void create_persistsEncryptedPassword() {
        JdbcConnector created = createConnector("enc-test");

        JdbcConnector loaded = service.getByPid(created.getPid());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getPassword())
                .as("password should be stored encrypted, not as plaintext 'secret'")
                .isNotEqualTo("secret");
    }

    @Test
    @DisplayName("getByPid returns the created connector")
    void getByPid_returnsCreated() {
        JdbcConnector created = createConnector("roundtrip");

        JdbcConnector found = service.getByPid(created.getPid());
        assertThat(found).isNotNull();
        assertThat(found.getPid()).isEqualTo(created.getPid());
        assertThat(found.getName()).isEqualTo("roundtrip");
        assertThat(found.getJdbcUrl()).isEqualTo("jdbc:mysql://localhost:3306/test");
    }

    @Test
    @DisplayName("listAll returns all connectors for the current tenant")
    void listAll_returnsAllForTenant() {
        int before = service.listAll().size();
        createConnector("list-a");
        createConnector("list-b");

        List<JdbcConnector> all = service.listAll();
        assertThat(all).hasSizeGreaterThanOrEqualTo(before + 2);
    }

    @Test
    @DisplayName("update changes name and re-encrypts password")
    void update_changesNameAndPassword() {
        JdbcConnector created = createConnector("to-update");

        JdbcConnectorCreateRequest updateReq = req();
        updateReq.setName("updated-name");
        updateReq.setPassword("new-secret");

        JdbcConnector updated = service.update(created.getPid(), updateReq);

        assertThat(updated.getName()).isEqualTo("updated-name");
        // Password must be re-encrypted, not stored as plaintext
        assertThat(updated.getPassword()).isNotEqualTo("new-secret");

        JdbcConnector reloaded = service.getByPid(created.getPid());
        assertThat(reloaded.getName()).isEqualTo("updated-name");
    }

    @Test
    @DisplayName("update throws IllegalArgumentException for unknown pid")
    void update_throwsForUnknownPid() {
        assertThatThrownBy(() -> service.update("no-such-pid", req()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connector not found");
    }

    @Test
    @DisplayName("delete cascades to endpoints and connector is gone")
    void delete_cascadesEndpoints() {
        JdbcConnector created = createConnector("to-delete");
        addEndpoint(created.getPid(), "ep-cascade");

        service.delete(created.getPid());

        assertThat(service.getByPid(created.getPid())).isNull();
        assertThat(service.listEndpoints(created.getPid())).isEmpty();
    }

    @Test
    @DisplayName("addEndpoint persists and listEndpoints returns it")
    void addEndpoint_persists() {
        JdbcConnector created = createConnector("endpoint-owner");
        JdbcConnectorEndpoint ep = addEndpoint(created.getPid(), "get-by-id");

        assertThat(ep).isNotNull();
        assertThat(ep.getCode()).isEqualTo("get-by-id");

        List<JdbcConnectorEndpoint> endpoints = service.listEndpoints(created.getPid());
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).getCode()).isEqualTo("get-by-id");
    }

    @Test
    @DisplayName("addEndpoint with duplicate code throws IllegalArgumentException")
    void addEndpoint_duplicateCodeThrows() {
        JdbcConnector created = createConnector("dup-owner");
        addEndpoint(created.getPid(), "same-code");

        assertThatThrownBy(() -> addEndpoint(created.getPid(), "same-code"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint code already exists");
    }
}
