package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
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
    @Test void exactCustomRoleActorsKeepTheirDifferentIntegrationAuthority() {
        ProcurementPublicSourceCommandRealDbIT.validateActorRoleIsolation(List.of(11L),11L,false,true,true);
        ProcurementPublicSourceCommandRealDbIT.validateActorRoleIsolation(List.of(12L),12L,false,false,false);
    }
    @Test void bootstrapAdminWildcardOrLeakedIntegrationCannotPassRoleIsolation() {
        assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.validateActorRoleIsolation(List.of(11L,99L),11L,false,true,true));
        assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.validateActorRoleIsolation(List.of(11L),11L,true,true,true));
        assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.validateActorRoleIsolation(List.of(12L),12L,false,true,false));
    }
    private Map<String,Object> replayFixture(long version,String time) {
        var row=new LinkedHashMap<String,Object>(Map.of("pid","source-A","row_version",version,"updated_at",time,
            "created_at","2066-01-01T00:00:00","updated_by","actor-A","remaining_qty","1000","revision",0));
        return new LinkedHashMap<>(Map.of("source",List.of(row),"task",List.of(Map.of("pid","task-A","row_version",1))));
    }
    @Test void exactDuplicateLockMetadataIsAcceptedWithoutChangingAnyBusinessFact() {
        ProcurementPublicSourceCommandRealDbIT.assertReplaySnapshot("source-A",replayFixture(1,"2066-01-01T00:00:00"),replayFixture(2,"2066-01-01T00:00:01"));
    }
    @Test void duplicateLockCannotHideBusinessOtherAuditOrOtherDomainChanges() {
        var before=replayFixture(1,"2066-01-01T00:00:00");
        for(String field:List.of("remaining_qty","revision","created_at","updated_by")) {
            var after=replayFixture(2,"2066-01-01T00:00:01");
            @SuppressWarnings("unchecked") var row=((List<Map<String,Object>>)after.get("source")).getFirst();row.put(field,"unexpected");
            assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.assertReplaySnapshot("source-A",before,after),field);
        }
        var other=replayFixture(2,"2066-01-01T00:00:01");other.put("task",List.of(Map.of("pid","task-A","row_version",2)));
        assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.assertReplaySnapshot("source-A",before,other));
    }
    @Test void duplicateLockRejectsWrongVersionBackwardTimeAndExtraOrReorderedSourceRows() {
        var before=replayFixture(1,"2066-01-01T00:00:00");
        for(long version:List.of(1L,3L)) assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.assertReplaySnapshot("source-A",before,replayFixture(version,"2066-01-01T00:00:01")));
        assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.assertReplaySnapshot("source-A",before,replayFixture(2,"2065-12-31T23:59:59")));
        var extra=replayFixture(2,"2066-01-01T00:00:01");
        @SuppressWarnings("unchecked") var row=((List<Map<String,Object>>)extra.get("source")).getFirst();
        extra.put("source",List.of(row,Map.of("pid","source-B","row_version",1,"updated_at","2066-01-01T00:00:00")));
        assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.assertReplaySnapshot("source-A",before,extra));
        var sibling=Map.<String,Object>of("pid","source-B","row_version",1,"updated_at","2066-01-01T00:00:00");
        @SuppressWarnings("unchecked") var old=((List<Map<String,Object>>)before.get("source")).getFirst();before.put("source",List.of(old,sibling));
        var reordered=replayFixture(2,"2066-01-01T00:00:01");
        @SuppressWarnings("unchecked") var next=((List<Map<String,Object>>)reordered.get("source")).getFirst();reordered.put("source",List.of(sibling,next));
        assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.assertReplaySnapshot("source-A",before,reordered));
        for(String field:List.of("row_version","updated_at")) {
            var changedSibling=new LinkedHashMap<>(sibling);changedSibling.put(field,field.equals("row_version")?2:"2066-01-01T00:00:02");
            var changed=replayFixture(2,"2066-01-01T00:00:01");changed.put("source",List.of(next,changedSibling));
            assertThrows(AssertionError.class,()->ProcurementPublicSourceCommandRealDbIT.assertReplaySnapshot("source-A",before,changed),"Non-target "+field+" must remain exact");
        }
    }
    @Test void safeNumericCodeAcceptsActualStringWireAndNeverPersistsFreeDiagnosticText() {
        assertEquals("35000",ProcurementPublicSourceCommandRealDbIT.safeNumericCode(JSON.getNodeFactory().textNode("35000")));
        assertEquals("403",ProcurementPublicSourceCommandRealDbIT.safeNumericCode(JSON.getNodeFactory().numberNode(403)));
        for(String value:List.of("secret=value","-1","1.0","9999999999999")) assertNull(ProcurementPublicSourceCommandRealDbIT.safeNumericCode(JSON.getNodeFactory().textNode(value)));
        assertNull(ProcurementPublicSourceCommandRealDbIT.safeNumericCode(JSON.createObjectNode()));
    }
}
