package com.auraboot.framework.workbench;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.release.ApplicationReleaseControlService;
import com.auraboot.framework.application.release.ApplicationReleaseRegistrationService;
import com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.AnnouncementDocumentCanonicalizer;
import com.auraboot.framework.plugin.extension.AnnouncementSealedContentAccessor.PublishedContent;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.approval.ApprovalContract.*;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.workbench.mapper.AnnouncementApprovalMapper;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import com.auraboot.framework.workbench.mapper.AnnouncementReleaseMapper;
import com.auraboot.framework.workbench.service.AnnouncementCodec;
import com.auraboot.framework.workbench.service.AnnouncementContentSealService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pf4j.PluginState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PG, release-backed permissions, PF4J discovery and the public command transaction.
 * Approval rows are controlled terminal-contract fixtures, not an actual BPM approval journey.
 * Service / real-stack / it; HTTP, browser and production consumer journeys are outside this class.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class AnnouncementSealedContentRichCommandIT {
    private static final String RUN = UniqueIdGenerator.generate().toLowerCase(Locale.ROOT);
    private static final String APP = "seal-native-" + RUN;
    private static final String MODEL = "seal_native_" + RUN;
    private static final String TABLE = "mt_" + MODEL;
    private static final String COMMAND = MODEL + ":consume";
    private static final String HANDLER = "seal-native:consume";
    private static final String PLUGIN = "com.auraboot.seal-native." + RUN;
    private static final String IMPLEMENTATION = "com.auraboot.plugins.sealfixture.SealNativeProbe";
    private static final String PUBLISHER = "seal_publisher";
    private static final String PROBE = "seal_probe";
    private static final String PUBLISH = "announcement.announcement.publish";
    private static final String INVOKE = "seal-native.invoke";
    private static final String READ = "model." + MODEL + ".read";
    private static final String CONTROL_ACTOR = "test:sealed-rich-" + RUN;
    private static final byte[] FILE_BYTES = "sealed native byte payload\r\n制度".getBytes(StandardCharsets.UTF_8);
    private static final OwnedFiles OWNED = prepareBundle();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> environment("TEST_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> environment("TEST_DATABASE_USERNAME"));
        properties.add("spring.datasource.password", () -> environment("TEST_DATABASE_PASSWORD"));
        properties.add("aura.application.definition-read.runtime-primary-enabled", () -> true);
        properties.add("aura.application.default-code", () -> APP);
        properties.add("aura.application.bundle-root", () -> OWNED.bundle().toString());
        properties.add("aura.application.definition-store", () -> OWNED.definitionStore().toString());
        properties.add("aura.plugins.dir", () -> OWNED.plugins().toString());
        properties.add("aura.storage.local.base-path", () -> OWNED.storage().toString());
        properties.add("auraboot.announcement.worker-enabled", () -> false);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationReleaseControlService releaseControl;
    @Autowired ApplicationRuntimeDefinitionCatalog catalog;
    @Autowired AuraPluginManager plugins;
    @Autowired ExtensionRegistry extensions;
    @Autowired CommandExecutor commands;
    @Autowired CommandService commandDefinitions;
    @Autowired MetaModelService models;
    @Autowired SchemaManagementService schema;
    @Autowired DynamicDataMapper rows;
    @Autowired UserService users;
    @Autowired TenantService tenants;
    @Autowired TenantMemberService members;
    @Autowired RoleService roles;
    @Autowired UserRoleService userRoles;
    @Autowired RoleMapper roleRows;
    @Autowired UserPermissionService permissions;
    @Autowired FileService files;
    @Autowired AnnouncementContentSealService content;
    @Autowired AnnouncementLifecycleMapper roots;
    @Autowired AnnouncementReleaseMapper releases;
    @Autowired AnnouncementApprovalMapper approvals;
    @Autowired AnnouncementCodec codec;

    private Tenant tenant;
    private Role publisherRole, probeRole;
    private Class<?> probeClass;
    private Path probeJar;
    private User actor;
    private TenantMember membership;
    private String target, announcement, version, snapshotHash, filePid;
    private Map<String, Object> snapshot;

    @BeforeAll
    void publishOwnedReleaseAndNativeCommand() throws Exception {
        assertOwnedDatabase();
        var registration = new ApplicationReleaseRegistrationService(jdbc, json);
        registration.createApplication(APP, "Sealed rich command " + RUN, CONTROL_ACTOR);
        var release = registration.register(APP, "sealed-rich-" + RUN,
                new ApplicationReleaseRegistrationService.Content(1, "1.0.0", OWNED.lockIdentity(),
                        json.createObjectNode().put("runtimeContract", "1"), List.of(
                        new ApplicationReleaseRegistrationService.Component("seal-permissions", "definition",
                                "1.0.0", OWNED.componentDigest(), null))), CONTROL_ACTOR);
        releaseControl.publish(APP, release.releaseId(), CONTROL_ACTOR, UniqueIdGenerator.generate());
        releaseControl.promoteStable(APP, release.releaseId(), null, CONTROL_ACTOR, UniqueIdGenerator.generate());

        var organization = new Tenant();
        organization.setPid(UniqueIdGenerator.generate()); organization.setName(APP);
        organization.setDisplayName("Sealed rich native fixture"); organization.setStatus("active");
        organization.setDeletedFlag(false); tenant = tenants.createTenant(organization);
        releaseControl.bindStable(tenant.getId(), APP, CONTROL_ACTOR, UniqueIdGenerator.generate());
        var owner = users.signUp("sealed-rich-owner-" + RUN + "@example.test", "Synthetic-fixture-42!");
        var ownerMember = members.addMember(owner.getId(), tenant.getId(), "active");
        identity(owner, ownerMember);
        publisherRole = role(PUBLISHER); probeRole = role(PROBE);
        models.saveDefinition(ModelDefinition.builder().code(MODEL).tableName(TABLE).status("published")
                .modelType("entity").fields(List.of(FieldDefinition.builder().code("evidence")
                        .columnName("evidence").dataType("string").maxLength(128).build())).build());
        var createdTable = schema.createTableByModel(MODEL);
        assertTrue(createdTable.isSuccess(), createdTable.getErrorMessage());

        probeJar = buildNativePlugin();
        assertEquals(PLUGIN, plugins.loadPlugin(probeJar));
        assertEquals(PluginState.STARTED, plugins.startPlugin(PLUGIN));
        var discovered = extensions.getCommandHandler(HANDLER).orElseThrow();
        assertEquals(IMPLEMENTATION, discovered.getClass().getName());
        var wrapper = plugins.getPlugin(PLUGIN);
        assertNotNull(wrapper);
        assertEquals(PluginState.STARTED, wrapper.getPluginState());
        assertSame(wrapper.getPluginClassLoader(), discovered.getClass().getClassLoader());
        assertSame(discovered, extensions.getCommandHandler(HANDLER).orElseThrow());
        // This generic fixture is not one of the fixed inventory/procurement privileged owners.
        assertFalse(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class, discovered));
        var artifact = plugins.observeLoadedArtifact(discovered.getClass()).orElseThrow();
        assertEquals(PLUGIN, artifact.pluginId());
        assertEquals(probeJar.toAbsolutePath().normalize(), artifact.path());
        assertEquals(prefixedHash(Files.readAllBytes(probeJar)), artifact.digest());
        probeClass = discovered.getClass();

        var definition = new CommandDefinitionCreateRequest();
        definition.setCode(COMMAND); definition.setModelCode(MODEL); definition.setDisplayName("Sealed native probe");
        definition.setExecutionConfig(json.writeValueAsString(Map.of("type", "custom", "handler", HANDLER,
                "permissions", List.of(INVOKE), "handlerParams", Map.of("dslPersistence", false,
                        "targetRecordAction", "update"))));
        commandDefinitions.publish(commandDefinitions.create(definition).getPid());
        MetaContext.clear();
    }

    @BeforeEach
    void newActorAndControlledSealedAnnouncement() throws Exception {
        assertOwnedDatabase();
        actor = users.signUp("sealed-rich-" + UniqueIdGenerator.generate().toLowerCase(Locale.ROOT)
                + "@example.test", "Synthetic-fixture-42!");
        membership = members.addMember(actor.getId(), tenant.getId(), "active");
        identity(actor, membership);
        assertTrue(userRoles.assignRolesToMemberByRolePids(membership.getPid(),
                List.of(probeRole.getPid(), publisherRole.getPid()), tenant.getId(), actor.getId()));
        assertEquals(Set.of(PROBE, PUBLISHER), activeRoleCodes());
        assertEquals(Set.of(PUBLISH), catalog.permissionsForRoles(tenant.getId(), APP, Set.of(PUBLISHER)));
        assertEquals(Set.of(INVOKE, READ), catalog.permissionsForRoles(tenant.getId(), APP, Set.of(PROBE)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE tenant_id=? "
                + "AND role_id IN (?,?) AND deleted_flag=false", Integer.class,
                tenant.getId(), probeRole.getId(), publisherRole.getId()));
        assertTrue(permissions.hasPermission(actor.getId(), PUBLISH));
        assertTrue(permissions.hasPermission(actor.getId(), INVOKE));
        assertTrue(permissions.hasPermission(actor.getId(), READ));

        var targetRow = new HashMap<String, Object>();
        target = UniqueIdGenerator.generate();
        targetRow.put("pid", target); targetRow.put("tenant_id", tenant.getId());
        targetRow.put("created_by", actor.getId()); targetRow.put("updated_by", actor.getId());
        targetRow.put("created_at", Instant.now()); targetRow.put("updated_at", Instant.now());
        targetRow.put("evidence", "before"); rows.insert(TABLE, targetRow);
        var upload = files.uploadFile(new MockMultipartFile("file", "sealed-native.txt", "text/plain", FILE_BYTES), actor.getId());
        filePid = upload.getFileId();
        var file = files.findByPid(filePid);
        announcement = UniqueIdGenerator.generate(); version = UniqueIdGenerator.generate();
        snapshot = Map.of("title", "制度 Native title ", "content", "<p>exact raw é\r\n正文</p>",
                "policy", Map.of("attachmentPids", List.of(filePid), "attachmentMetadata", List.of(Map.of(
                        "fileId", filePid, "name", file.getOriginalName(), "size", file.getFileSize(), "type", file.getMimeType()))));
        snapshotHash = codec.hash(snapshot);
        freezeControlledApproval();
        probeClass.getMethod("reset").invoke(null);
    }

    @AfterEach void clearIdentity() { MetaContext.clear(); }

    @AfterAll
    void unloadOnlyOwnedNativePlugin() {
        try {
            if (plugins.getPlugin(PLUGIN) != null) {
                plugins.stopPlugin(PLUGIN); plugins.unloadPlugin(PLUGIN);
            }
        } finally { MetaContext.clear(); }
    }

    @Test
    void nativePublisherProofCommitsThroughTheRealRichHost() throws Exception {
        var result = execute("normal");
        assertEquals("completed", result.getPhaseReached());
        assertFalse(result.isIdempotentReplay());
        assertEquals(true, result.getData().get("sealedBridge"));
        assertProbe(1, 1, false);
        var proof = (PublishedContent) probeClass.getField("lastProof").get(null);
        assertNotNull(proof);
        assertEquals(announcement, proof.exactVersion().announcementPid());
        assertEquals(version, proof.exactVersion().versionPid());
        assertEquals(snapshotHash, proof.snapshotHash());
        assertEquals(membership.getPid(), proof.actorMemberPid());
        assertEquals(snapshot.get("title"), proof.document().title());
        assertEquals(snapshot.get("content"), proof.document().body());
        assertEquals(AnnouncementDocumentCanonicalizer.encode(proof.document()), proof.encoded());
        assertNotEquals(snapshotHash, proof.encoded().documentDigest());
        assertEquals(proof.encoded().documentDigest(), result.getData().get("documentDigest"));
        var attachment = proof.document().attachments().getFirst();
        assertEquals(1, proof.document().attachments().size());
        assertEquals(filePid, attachment.filePid());
        assertEquals("sealed-native.txt", attachment.name()); assertEquals("text/plain", attachment.mediaType());
        assertEquals(FILE_BYTES.length, attachment.byteLength()); assertEquals(rawHash(FILE_BYTES), attachment.sha256());
        assertEquals(AnnouncementDocumentCanonicalizer.SourceIdentityKind.SEAL_OBSERVED_CONTENT, attachment.sourceIdentityKind());
        assertEquals("sealed-proof", marker());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_announcement_recipient "
                + "WHERE tenant_id=? AND version_pid=?", Integer.class, tenant.getId(), version));
    }

    @Test
    void sameTransactionPublisherAssignmentRevocationRejectsCommitAndRollsBackCaller() throws Exception {
        assertPublisherDenial(assertThrows(RuntimeException.class, () -> execute("revoke")));
        assertProbe(1, 1, true);
        assertEquals("before", marker());
        assertEquals(Set.of(PROBE, PUBLISHER), activeRoleCodes());
        var association = userRoles.findByMemberIdAndRoleIdAndTenantId(membership.getId(), publisherRole.getId(), tenant.getId());
        assertNotNull(association); assertEquals("active", association.getStatus());
        assertFalse(Boolean.TRUE.equals(association.getDeletedFlag()));
        assertTrue(permissions.hasPermission(actor.getId(), PUBLISH));
        assertTrue(permissions.hasPermission(actor.getId(), INVOKE));
        assertTrue(permissions.hasPermission(actor.getId(), READ));
    }

    @Test
    void committedPublisherAssociationRevocationStillRunsProbeButRefusesContent() throws Exception {
        assertTrue(userRoles.removeRolesFromMemberByRolePids(membership.getPid(),
                List.of(publisherRole.getPid()), tenant.getId()));
        assertEquals(Set.of(PROBE), activeRoleCodes());
        assertEquals(Set.of(PUBLISH), catalog.permissionsForRoles(tenant.getId(), APP, Set.of(PUBLISHER)));
        assertFalse(permissions.hasPermission(actor.getId(), PUBLISH));
        assertTrue(permissions.hasPermission(actor.getId(), INVOKE));
        assertTrue(permissions.hasPermission(actor.getId(), READ));
        assertPublisherDenial(assertThrows(RuntimeException.class, () -> execute("normal")));
        assertProbe(1, 0, false);
        assertEquals("before", marker());
        assertEquals(Set.of(PROBE), activeRoleCodes());
    }

    private com.auraboot.framework.meta.dto.CommandExecuteResult execute(String mode) {
        var request = new CommandExecuteRequest();
        request.setTargetRecordId(target);
        request.setExpectedVersion(jdbc.queryForObject("SELECT row_version FROM " + TABLE
                + " WHERE tenant_id=? AND pid=?", Integer.class, tenant.getId(), target));
        request.setClientRequestId("sealed-rich-" + UniqueIdGenerator.generate());
        request.setPayload(Map.of("announcementPid", announcement, "versionPid", version,
                "snapshotHash", snapshotHash, "mode", mode));
        return commands.execute(COMMAND, request);
    }

    private void assertProbe(int entered, int proofs, boolean revoked) throws Exception {
        assertEquals(entered, probeClass.getField("entered").getInt(null));
        assertEquals(true, probeClass.getField("getterSeen").get(null));
        assertEquals(proofs, probeClass.getField("proofIssued").getInt(null));
        assertEquals(revoked, probeClass.getField("revocationReached").get(null));
    }

    private void assertPublisherDenial(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AccessDeniedException) {
                assertEquals("Announcement action is not permitted for the current membership", cause.getMessage());
                return;
            }
        }
        fail("Expected the official publisher access refusal", failure);
    }

    private String marker() {
        return new TransactionTemplate(transactions).execute(status -> jdbc.queryForObject(
                "SELECT evidence FROM " + TABLE + " WHERE tenant_id=? AND pid=?", String.class, tenant.getId(), target));
    }

    private Set<String> activeRoleCodes() {
        var result = new HashSet<String>();
        roleRows.findByMemberIdAndTenantId(membership.getId(), tenant.getId()).forEach(role -> result.add(role.getCode()));
        return Set.copyOf(result);
    }

    private Role role(String code) {
        var role = new Role(); role.setTenantId(tenant.getId()); role.setCode(code); role.setName(code);
        role.setType("custom"); role.setScopeType("tenant"); role.setRoleScope("tenant");
        role.setPriority(100); role.setIsDefault(false); return roles.createRole(role);
    }

    private void identity(User user, TenantMember member) {
        MetaContext.setContext(tenant.getId(), user.getId(), user.getPid(), user.getUserName());
        MetaContext.setMemberId(member.getId());
    }

    private void assertOwnedDatabase() throws Exception {
        assertEquals("maritime-parallel-core", environment("AURA_RUNTIME_NAME"));
        assertEquals("auraboot_26", environment("POSTGRES_DB"));
        assertEquals("auraboot_26", jdbc.queryForObject("SELECT current_database()", String.class));
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertEquals("/auraboot_26", URI.create(connection.getMetaData().getURL().substring(5)).getPath());
        }
    }

    private void freezeControlledApproval() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            content.lockActor();
            assertEquals(1, roots.insertDraft(tenant.getId(), actor.getId(), announcement,
                    "制度 Native title ", "<p>raw</p>", "normal", false, null));
            assertNotNull(roots.lock(tenant.getId(), announcement));
            assertEquals(1, releases.freeze(tenant.getId(), announcement, version, 1, snapshotHash, codec.encode(snapshot), actor.getId()));
            assertTrue(files.lockRetention(filePid));
            content.captureFrozenVersion(tenant.getId(), announcement, version, snapshotHash, codec.encode(snapshot));
            String submission = UniqueIdGenerator.generate(), result = UniqueIdGenerator.generate(), definition = UniqueIdGenerator.generate();
            var binding = new SubmittedApproval(submission, "fixture-operation", new BusinessRef("announcement", announcement, version),
                    "snapshot", snapshotHash, "announcement.v1", new DefinitionRef(definition, 1, "a".repeat(64)),
                    StrictPolicy.required(), actor.getPid(), "制度 Native title ", Instant.now());
            assertEquals(1, approvals.insert(tenant.getId(), submission, announcement, version, actor.getId(), membership.getPid(),
                    actor.getPid(), "controlled-test-fixture", "Fixture definition", codec.encode(binding), codec.hash(binding)));
            var terminal = new TerminalResult(submission, binding.businessRef(), snapshotHash, "fixture-instance", binding.definition(),
                    "approved", result, 1, Instant.now(), "CONTROLLED_TEST_FIXTURE");
            jdbc.update("UPDATE ab_announcement_approval_submission SET state='approved',sync_state='completed',instance_pid='fixture-instance',"
                    + "provider_revision=1,terminal_result_pid=?,terminal_result=CAST(? AS jsonb) WHERE tenant_id=? AND pid=?",
                    result, codec.encode(terminal), tenant.getId(), submission);
            jdbc.update("INSERT INTO ab_announcement_version_control(tenant_id,version_pid,approval_state,publication_state,"
                    + "authorization_basis,current_submission_pid,published_at) VALUES(?,?,'approved','published','approved',?,clock_timestamp())",
                    tenant.getId(), version, submission);
            jdbc.update("UPDATE ab_announcement SET current_version_pid=?,active_version_pid=?,status='published',published_at=clock_timestamp() "
                    + "WHERE tenant_id=? AND pid=?", version, version, tenant.getId(), announcement);
        });
    }

    private Path buildNativePlugin() throws Exception {
        Path source = OWNED.root().resolve("SealNativeProbe.java"), classes = OWNED.root().resolve("classes");
        Files.createDirectories(classes);
        Files.writeString(source, """
                package com.auraboot.plugins.sealfixture;
                import com.auraboot.framework.plugin.extension.*;
                import java.util.*;
                public final class SealNativeProbe implements CommandHandlerExtension {
                    public static volatile int entered, proofIssued;
                    public static volatile boolean getterSeen, revocationReached;
                    public static volatile AnnouncementSealedContentAccessor.PublishedContent lastProof;
                    public static void reset() { entered=0; proofIssued=0; getterSeen=false; revocationReached=false; lastProof=null; }
                    public String getCommandType() { return "seal-native:consume"; }
                    public Object execute(CommandContext context) {
                        entered++;
                        var bridge=Objects.requireNonNull(context.announcementSealedContentAccessor(), "sealed getter missing");
                        if(context.recordActionScopes()==null || context.commandMembershipLockAccessor()==null
                                || context.announcementAcknowledgementAccessor()==null) throw new AssertionError("rich getters missing");
                        getterSeen=true;
                        var p=context.payload();
                        var proof=bridge.requirePublishedContentForCommand(new AnnouncementSealedContentAccessor.ExactVersion(
                                (String)p.get("announcementPid"),(String)p.get("versionPid"),(String)p.get("snapshotHash"),
                                AnnouncementDocumentCanonicalizer.SCHEMA));
                        lastProof=proof; proofIssued++;
                        context.dataAccessor().update(context.modelCode(),context.recordId(),Map.of("evidence","sealed-proof"));
                        if("revoke".equals(p.get("mode"))) {
                            context.tenantRoleAssignmentAccessor().setRole(context.currentUserPid(),"seal_publisher",false);
                            revocationReached=true;
                        }
                        return Map.of("sealedBridge",true,"documentDigest",proof.encoded().documentDigest());
                    }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK is required for the owned native plugin fixture");
        assertEquals(0, compiler.run(null, null, null, "-proc:none", "-classpath", compilerClasspath(),
                "-d", classes.toString(), source.toString()));
        var manifest = new Manifest(); var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0"); attributes.putValue("Plugin-Id", PLUGIN);
        attributes.putValue("Plugin-Version", "1.0.0"); attributes.putValue("Plugin-Class", "org.pf4j.Plugin");
        Path jar = OWNED.plugins().resolve("sealed-native-fixture.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            try (var paths = Files.walk(classes)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    output.write(Files.readAllBytes(file)); output.closeEntry();
                }
            }
            output.putNextEntry(new JarEntry("META-INF/extensions.idx"));
            output.write((IMPLEMENTATION + "\n").getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
        return jar;
    }

    private static String compilerClasspath() throws Exception {
        var paths = new LinkedHashSet<>(List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        // Gradle workers may put test dependencies in a child loader rather than java.class.path.
        // Compile against the exact API/Core classes loaded by this host, never a separately published API.
        for (Class<?> type : List.of(CommandHandlerExtension.class, AnnouncementDocumentCanonicalizer.class,
                CommandExecuteRequest.class, org.pf4j.ExtensionPoint.class)) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        return String.join(java.io.File.pathSeparator, paths);
    }

    private record OwnedFiles(Path root, Path bundle, Path definitionStore, Path plugins, Path storage,
                              String lockIdentity, String componentDigest) {}

    private static OwnedFiles prepareBundle() {
        try {
            Path root = Files.createTempDirectory("aura-sealed-rich-"), bundle = root.resolve("bundle");
            Path component = bundle.resolve("config/seal-permissions"), store = root.resolve("definition-store");
            Path plugins = root.resolve("plugins"), storage = root.resolve("storage");
            for (Path directory : List.of(component, store, plugins, storage)) Files.createDirectories(directory);
            var mapper = new ObjectMapper();
            mapper.writeValue(component.resolve("plugin.json").toFile(), Map.of(
                    "pluginId", "com.auraboot.seal-permissions-it", "namespace", "sealperm", "version", "1.0.0",
                    "roles", List.of(Map.of("code", PUBLISHER, "name", "Sealed publisher", "permissions", List.of(PUBLISH)),
                            Map.of("code", PROBE, "name", "Sealed native probe", "permissions", List.of(INVOKE, READ)))));
            String componentDigest = directoryDigest(component);
            ObjectNode lock = mapper.valueToTree(Map.of("schemaVersion", 1, "application", Map.of("id", APP),
                    "artifacts", List.of(Map.of("type", "config", "id", "seal-permissions", "digest", componentDigest,
                            "localPath", "config/seal-permissions"))));
            String lockIdentity = prefixedHash(mapper.writeValueAsBytes(canonical(mapper, lock)));
            lock.put("identity", lockIdentity); mapper.writeValue(bundle.resolve("application.lock").toFile(), lock);
            return new OwnedFiles(root, bundle, store, plugins, storage, lockIdentity, componentDigest);
        } catch (Exception failure) { throw new IllegalStateException("Cannot prepare the owned immutable definition fixture", failure); }
    }

    private static String directoryDigest(Path directory) throws Exception {
        var entries = new ArrayList<String>();
        try (var paths = Files.walk(directory)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString())).toList()) {
                entries.add(directory.relativize(file).toString().replace('\\', '/') + "\0" + prefixedHash(Files.readAllBytes(file)));
            }
        }
        return prefixedHash((String.join("\n", entries) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode canonical(ObjectMapper mapper, JsonNode value) {
        if (value.isObject()) {
            var result = mapper.createObjectNode(); var fields = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> result.set(key, canonical(mapper, child))); return result;
        }
        if (value.isArray()) {
            var result = mapper.createArrayNode(); value.forEach(child -> result.add(canonical(mapper, child))); return result;
        }
        return value;
    }

    private static String rawHash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String prefixedHash(byte[] bytes) throws Exception { return "sha256:" + rawHash(bytes); }
    private static String environment(String name) { return Objects.requireNonNull(System.getenv(name), "Managed runtime requires " + name); }
}
