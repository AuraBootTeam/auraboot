package com.auraboot.framework.bpm.rule;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmRule;
import com.auraboot.framework.bpm.mapper.BpmRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.StatelessKieSession;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DroolsEngineService {

    /** Dangerous imports that could enable RCE or info leakage */
    private static final List<Pattern> BLOCKED_DRL_PATTERNS = List.of(
            Pattern.compile("import\\s+java\\.lang\\.Runtime"),
            Pattern.compile("import\\s+java\\.lang\\.ProcessBuilder"),
            Pattern.compile("import\\s+java\\.lang\\.System"),
            Pattern.compile("import\\s+java\\.lang\\.reflect\\."),
            Pattern.compile("import\\s+java\\.io\\."),
            Pattern.compile("import\\s+java\\.nio\\."),
            Pattern.compile("import\\s+java\\.net\\."),
            Pattern.compile("import\\s+javax\\.script\\."),
            Pattern.compile("import\\s+javax\\.management\\."),
            Pattern.compile("Runtime\\.getRuntime\\("),
            Pattern.compile("ProcessBuilder"),
            Pattern.compile("System\\.exit"),
            Pattern.compile("System\\.getenv"),
            Pattern.compile("System\\.getProperty"),
            Pattern.compile("Class\\.forName"),
            Pattern.compile("\\.getClass\\(\\)\\."),
            Pattern.compile("java\\.lang\\.Thread")
    );

    /**
     * Drools 8.x KieBuilderImpl scans the classpath for Maven pom.xml files inside the
     * Drools JARs themselves (META-INF/maven/org.drools/drools-compiler/pom.xml, etc.) and
     * reports them as ERROR-level messages when it cannot parse them as Kie module descriptors.
     * These messages are not DRL compilation errors — they are project-discovery noise.
     * We filter messages whose path ends with these suffixes.
     */
    private static final Set<String> IGNORED_MESSAGE_PATH_SUFFIXES = Set.of("pom.xml", "kmodule.xml");

    private static final int MAX_CACHE_SIZE = 200;

    private final BpmRuleMapper ruleMapper;
    private final ConcurrentHashMap<String, KieBase> kieBaseCache = new ConcurrentHashMap<>();

    /**
     * Evaluate a rule by its code with the given facts.
     */
    public Map<String, Object> evaluate(String ruleCode, Map<String, Object> facts) {
        Long tenantId = MetaContext.getCurrentTenantId();
        BpmRule rule = ruleMapper.findByCode(tenantId, ruleCode);
        if (rule == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleCode);
        }
        return evaluateRule(rule, facts);
    }

    /**
     * Evaluate a rule directly.
     */
    public Map<String, Object> evaluateRule(BpmRule rule, Map<String, Object> facts) {
        KieBase kieBase = getOrBuildKieBase(rule);
        StatelessKieSession session = kieBase.newStatelessKieSession();

        // Create result holder
        Map<String, Object> result = new HashMap<>(facts);
        result.put("_ruleResult", new HashMap<String, Object>());

        // Execute with facts
        List<Object> factsList = new ArrayList<>();
        factsList.add(result);
        // Add individual facts
        for (Map.Entry<String, Object> entry : facts.entrySet()) {
            factsList.add(entry.getValue());
        }

        session.execute(factsList);

        @SuppressWarnings("unchecked")
        Map<String, Object> ruleResult = (Map<String, Object>) result.getOrDefault("_ruleResult", Map.of());
        log.debug("Rule evaluated: code={}, result={}", rule.getRuleCode(), ruleResult);
        return ruleResult;
    }

    private KieBase getOrBuildKieBase(BpmRule rule) {
        String cacheKey = rule.getPid() + ":" + rule.getVersion();
        return kieBaseCache.computeIfAbsent(cacheKey, k -> {
            // Enforce cache size limit
            if (kieBaseCache.size() >= MAX_CACHE_SIZE) {
                var it = kieBaseCache.entrySet().iterator();
                int toRemove = kieBaseCache.size() / 4; // remove 25%
                for (int i = 0; i < toRemove && it.hasNext(); i++) {
                    it.next();
                    it.remove();
                }
                log.info("KieBase cache evicted {} entries (size was {})", toRemove, MAX_CACHE_SIZE);
            }

            // Security check before compiling DRL
            validateDrlSecurity(rule.getRuleContent());

            log.info("Building KieBase for rule: code={}, version={}", rule.getRuleCode(), rule.getVersion());
            return buildKieBase(rule.getRuleContent(), rule.getRuleCode(), rule.getVersion());
        });
    }

    /**
     * Build a KieBase from DRL content using KieServices with KieFileSystem.
     *
     * <p>Uses a unique ReleaseId per rule+version so that KieBuilderImpl treats each rule
     * as a distinct in-memory Kie module. Drools' classpath scanner may still report
     * pom.xml/kmodule.xml entries from the Drools JARs themselves as ERROR-level messages;
     * these are filtered out — only genuine DRL compilation errors abort the build.
     */
    private KieBase buildKieBase(String drlContent, String ruleCode, int version) {
        KieServices ks = KieServices.Factory.get();

        // Unique groupId per rule+version: forces a fresh in-memory Kie module
        ReleaseId releaseId = ks.newReleaseId(
                "com.auraboot.rules." + ruleCode,
                ruleCode,
                String.valueOf(version)
        );

        KieFileSystem kfs = ks.newKieFileSystem();
        // generateAndWritePomXML registers this module in KieRepository and writes a
        // synthetic pom.xml into the KFS, which prevents KieBuilderImpl from falling back
        // to a classpath Maven project scan for THIS module's identity.
        kfs.generateAndWritePomXML(releaseId);

        // kmodule.xml required by KieBuilderImpl XML support (drools-xml-support on CP)
        kfs.writeKModuleXML(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<kmodule xmlns=\"http://www.drools.org/xsd/kmodule\"/>"
        );

        // DRL at src/main/resources/<pkgPath>/<ruleCode>.drl — matching its package decl
        String packagePath = extractDrlPackagePath(drlContent);
        kfs.write("src/main/resources/" + packagePath + "/" + ruleCode + ".drl", drlContent);

        // Serialize KieBuilder construction and DRL compilation to prevent concurrent calls
        // from overwriting each other's default-releaseId registration in KieRepository.
        // KieBuilderImpl.buildPomModel() may fail to parse the generated pom.xml and fall
        // back to the global default ReleaseId; holding this lock ensures only one rule is
        // in-flight at a time so the KieRepository lookup returns the correct module.
        synchronized (DroolsEngineService.class) {
            org.kie.internal.builder.InternalKieBuilder kieBuilder =
                    (org.kie.internal.builder.InternalKieBuilder) ks.newKieBuilder(kfs);
            kieBuilder.buildAll();

            // Filter out pom.xml / kmodule.xml discovery noise — these come from Drools JARs'
            // own META-INF/maven/pom.xml entries found on the classpath, not from our DRL.
            List<Message> realErrors = kieBuilder.getResults().getMessages(Message.Level.ERROR).stream()
                    .filter(m -> !isClasspathDiscoveryNoise(m))
                    .toList();

            if (!realErrors.isEmpty()) {
                throw new RuntimeException("DRL compilation errors for rule '" + ruleCode + "': " + realErrors);
            }

            // getKieModuleIgnoringErrors() returns the compiled KieModule even when the pom.xml
            // parse produced an error message. Register it explicitly under our custom releaseId
            // so that newKieContainer(releaseId) can find it.
            org.kie.api.builder.KieModule kieModule = kieBuilder.getKieModuleIgnoringErrors();
            if (kieModule == null) {
                throw new RuntimeException("Drools failed to build KieModule for rule '" + ruleCode +
                        "' (kModule is null after buildAll). Errors: " + kieBuilder.getResults().getMessages());
            }
            // kieModule may have been assigned the global default ReleaseId when our custom
            // pom.xml failed to parse. Use the kModule's ACTUAL releaseId rather than our
            // custom one to look it up — the KieRepository stores it under that actual id.
            org.kie.api.builder.ReleaseId actualReleaseId = kieModule.getReleaseId();
            ks.getRepository().addKieModule(kieModule);
            log.debug("Built KieModule for rule '{}' under releaseId: {}", ruleCode, actualReleaseId);

            KieContainer container = ks.newKieContainer(actualReleaseId);
            return container.getKieBase();
        }
    }

    /**
     * Returns true if the KieBuilder message is classpath-discovery noise rather than a
     * genuine DRL compilation error. Drools 8.x reports pom.xml and kmodule.xml entries
     * from its own JARs as ERROR-level messages when it cannot parse them as Kie modules.
     */
    private boolean isClasspathDiscoveryNoise(Message m) {
        String path = m.getPath();
        if (path == null) return false;
        for (String suffix : IGNORED_MESSAGE_PATH_SUFFIXES) {
            if (path.endsWith(suffix)) {
                log.debug("Ignoring Drools classpath discovery message [{}]: {}", path, m.getText());
                return true;
            }
        }
        return false;
    }

    /**
     * Extract the package declaration from DRL content and convert it to a file path.
     * For example, "com.auraboot.workflow_demo" becomes "com/auraboot/workflow_demo".
     * Returns "rules" as a fallback if no package declaration is found.
     */
    private String extractDrlPackagePath(String drlContent) {
        if (drlContent == null) return "rules";
        for (String line : drlContent.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                String pkg = trimmed.substring("package".length()).trim().replace(";", "").trim();
                return pkg.replace('.', '/');
            }
        }
        return "rules";
    }

    /**
     * Validate DRL content does not contain dangerous Java calls that could lead to RCE.
     */
    private void validateDrlSecurity(String drlContent) {
        for (Pattern pattern : BLOCKED_DRL_PATTERNS) {
            if (pattern.matcher(drlContent).find()) {
                throw new IllegalArgumentException(
                        "DRL rule contains blocked pattern (potential security risk): " + pattern.pattern());
            }
        }
    }

    /**
     * Invalidate cache for a rule.
     */
    public void invalidateCache(String rulePid) {
        kieBaseCache.keySet().removeIf(key -> key.startsWith(rulePid + ":"));
        log.info("Cache invalidated for rule: pid={}", rulePid);
    }

    /**
     * Validate DRL syntax.
     */
    public List<String> validateDrl(String drlContent) {
        List<String> errors = new ArrayList<>();

        // Security validation first
        for (Pattern pattern : BLOCKED_DRL_PATTERNS) {
            if (pattern.matcher(drlContent).find()) {
                errors.add("Security: blocked pattern detected — " + pattern.pattern());
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }

        // Syntax validation — pom.xml noise is filtered in buildKieBase
        try {
            buildKieBase(drlContent, "validated_" + System.nanoTime(), 0);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            errors.add(msg != null ? msg : "Unknown compilation error");
        }
        return errors;
    }
}
