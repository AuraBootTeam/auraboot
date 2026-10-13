package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Pure input-contract checks: no Spring context, authentication, plugin execution, or database. */
class ProcurementPublicSourceReadinessTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<String> SOURCES=List.of("oss","quote","enterprise","crm","plugins");
    private ObjectNode fixture() throws Exception {
        String actual=System.getenv("PROCUREMENT_PUBLIC_SOURCE_PREFLIGHT_FIXTURE");
        if(actual!=null) return (ObjectNode)JSON.readTree(Files.readAllBytes(Path.of(actual)));
        var result=JSON.createObjectNode();var set=result.putObject("sourceSet");var verified=result.putObject("verifiedSourceIdentities");var shortIds=result.putObject("manifestCommits");
        for(int index=0;index<SOURCES.size();index++) {
            String component=SOURCES.get(index),full=String.valueOf(index+1).repeat(40);
            set.put(component,full);shortIds.put(component,full.substring(0,12));
            verified.putObject(component).put("status","ok").put("expectedCommit",full).put("actualCommit",full)
                .put("expectedDirtyFingerprint","clean").put("actualDirtyFingerprint","clean");
        }
        return result;
    }
    private void verify(JsonNode input,String component) {
        ProcurementPublicSourceCommandRealDbIT.validateSourceIdentity(component,input.path("manifestCommits").path(component).asText(),
            input.path("sourceSet").path(component).asText(),input);
    }
    @Test void canonicalTwelveDigitManifestResolvesAllFiveFullVerifiedSourceIdentities() throws Exception {
        var input=fixture();for(String source:SOURCES) verify(input,source);
        assertFalse(input.path("manifestCommits").path("oss").asText().matches("[a-f0-9]{40}"),"Old preparation assumption rejects this canonical short wire");
    }
    @Test void matchingShortPrefixCannotHideWrongSealedExpectedOrActualFullCommit() throws Exception {
        var original=fixture();String full=original.path("sourceSet").path("oss").asText();String wrong=full.substring(0,39)+(full.endsWith("f")?"e":"f");
        var sealed=original.deepCopy();((ObjectNode)sealed.path("sourceSet")).put("oss",wrong);
        assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.validateSourceIdentity("oss",original.path("manifestCommits").path("oss").asText(),full,sealed));
        for(String field:List.of("expectedCommit","actualCommit")) {
            var changed=original.deepCopy();((ObjectNode)changed.path("verifiedSourceIdentities").path("oss")).put(field,wrong);
            assertThrows(AssertionError.class,()->verify(changed,"oss"),field+" must be a full exact match");
        }
    }
    @Test void partialMalformedOrWrongManifestCommitIsRejected() throws Exception {
        var input=fixture();String shortId=input.path("manifestCommits").path("oss").asText();
        for(String invalid:List.of(shortId.substring(0,11),shortId+"f",shortId.toUpperCase()+"G",input.path("sourceSet").path("oss").asText(),"0".repeat(12))) {
            assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.validateSourceIdentity("oss",invalid,input.path("sourceSet").path("oss").asText(),input));
        }
    }
    @Test void missingVerificationDirtyOrUnverifiedSourceCannotBeAccepted() throws Exception {
        var original=fixture();var missing=original.deepCopy();missing.remove("verifiedSourceIdentities");assertThrows(AssertionError.class,()->verify(missing,"oss"));
        for(String field:List.of("status","expectedDirtyFingerprint","actualDirtyFingerprint")) {
            var changed=original.deepCopy();((ObjectNode)changed.path("verifiedSourceIdentities").path("oss")).put(field,"not-verified");
            assertThrows(AssertionError.class,()->verify(changed,"oss"),field+" must be verified");
        }
    }
}
