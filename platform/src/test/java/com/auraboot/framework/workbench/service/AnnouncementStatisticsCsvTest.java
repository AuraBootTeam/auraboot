package com.auraboot.framework.workbench.service;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AnnouncementStatisticsCsvTest {
    @TempDir Path temporary;
    private Map<String,String> labels() {
        var labels=new HashMap<String,String>();
        AnnouncementStatisticsCsv.COLUMNS.forEach(key->labels.put("column."+key,key));
        AnnouncementStatisticsCsv.STATES.forEach(key->labels.put("state."+key,key));
        return labels;
    }
    private Map<String,Object> metadata() {
        var metadata=new HashMap<String,Object>();
        metadata.put("timezone","America/New_York");metadata.put("title"," =HYPERLINK(\"bad\")");
        metadata.put("versionNumber",3);metadata.put("publishedAt","2026-07-01T12:00:00Z");
        metadata.put("acknowledgementRequired",false);metadata.put("acknowledgementDeadline",null);
        return metadata;
    }
    private Map<String,Object> row() {
        var row=new HashMap<String,Object>();
        row.put("displayName","张三,\"QA\"\r\n第二行");row.put("departments",List.of());row.put("matchedRoles",List.of());
        row.put("readState","unknown");row.put("firstReadAt",null);row.put("acknowledgementState","not_required");
        row.put("acknowledgedAt",null);row.put("lateAcknowledgement",false);row.put("memberEligible",false);
        row.put("notificationState","not_generated");return row;
    }
    @Test void exactUtf8BomRfc4180BytesDistinguishUnknownFromNotApplicableAndExplicitFrozenTimezone() throws Exception {
        Path output=Files.createTempFile(temporary,"csv-",".csv");
        try(var csv=new AnnouncementStatisticsCsv(output,metadata(),labels(),10000)) {
            csv.row(row(),Map.of("captured",false),Map.of("departmentPids",List.of()));
        }
        assertThat(Files.readString(output)).isEqualTo("\uFEFFtitle,version,published,recipient,departments,roles,readState,firstRead,ackRequired,ackDeadline,ackState,ackAt,late,membership,notification\r\n"
            +"\"' =HYPERLINK(\"\"bad\"\")\",3,2026-07-01T08:00:00-04:00 [America/New_York],\"张三,\"\"QA\"\"\r\n第二行\",unknown,unknown,unknown,unknown,no,not_applicable,not_required,not_applicable,not_applicable,inactive,not_generated\r\n");
        assertThat(Files.readAllBytes(output)).startsWith((byte)0xef,(byte)0xbb,(byte)0xbf);
    }
    @Test void hiddenClassificationsDoNotBecomeNoneAndRequiredPendingIsNotNotApplicable() throws Exception {
        Path output=Files.createTempFile(temporary,"csv-",".csv");var metadata=metadata();metadata.put("title","Normal");
        metadata.put("acknowledgementRequired",true);metadata.put("acknowledgementDeadline","2026-11-01T07:00:00Z");
        var row=row();row.put("readState","unread");row.put("acknowledgementState","pending_ack");row.put("notificationState","failed");
        try(var csv=new AnnouncementStatisticsCsv(output,metadata,labels(),10000)) {
            csv.row(row,Map.of("captured",true,"departmentPids",List.of("private-dept")),Map.of("departmentPids",List.of()));
        }
        assertThat(Files.readString(output)).contains(",hidden,none,unread,not_yet,yes,2026-11-01T02:00:00-05:00 [America/New_York],pending_ack,not_yet,no,inactive,failed\r\n")
            .doesNotContain("private-dept");
    }
    @Test void missingTranslationUnknownStateAndInconsistentProofFailRatherThanInventingValues() throws Exception {
        Path output=Files.createTempFile(temporary,"csv-",".csv");var labels=labels();labels.remove("state.unknown");
        assertThatThrownBy(()->new AnnouncementStatisticsCsv(output,metadata(),labels,10000)).hasMessageContaining("translation");
        try(var csv=new AnnouncementStatisticsCsv(output,metadata(),labels(),10000)) {
            var row=row();row.put("notificationState","new_unmapped_status");
            assertThatThrownBy(()->csv.row(row,Map.of("captured",false),Map.of("departmentPids",List.of())))
                .hasMessageContaining("Unknown statistics CSV state");
            assertThatThrownBy(()->csv.row(row(),Map.of("captured",false),Map.of("departmentPids",List.of("hidden"))))
                .hasMessageContaining("display proof");
        }
    }
    @Test void byteLimitCountsUtf8AndNeverFollowsAnOutputSymlink() throws Exception {
        Path output=Files.createTempFile(temporary,"csv-",".csv");
        assertThatThrownBy(()->new AnnouncementStatisticsCsv(output,metadata(),labels(),2)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.size(output)).isZero();
        Files.writeString(output,"protected");Path link=temporary.resolve("link.csv");Files.createSymbolicLink(link,output);
        assertThatThrownBy(()->new AnnouncementStatisticsCsv(link,metadata(),labels(),10000)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(output)).isEqualTo("protected");
    }
}
