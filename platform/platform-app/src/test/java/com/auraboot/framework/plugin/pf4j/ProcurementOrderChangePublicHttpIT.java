package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import static org.junit.jupiter.api.Assertions.*;

/** Real managed HTTP/installed delivery/PostgreSQL, isolated fixture identities and synthetic ERP HTTP only. */
@EnabledIfEnvironmentVariable(named="PROCUREMENT_ORDER_CHANGE_PUBLIC",matches="true")
class ProcurementOrderChangePublicHttpIT extends ProcurementOrderChangeNativeDbIT {
    @Autowired CloudConfigService configs;
    HttpServer erp;
    HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    Path privateDirectory;
    String admin,installation,integrationToken,taskPid,executionPid;
    final Queue<Map<String,Object>> providerCalls=new ConcurrentLinkedQueue<>();
    volatile ObjectNode providerSnapshot;
    int requestNumber;
    String base;

    @Override String create(String writer,String model,Map<String,Object> values){
        if("pr_purchase_request".equals(model) && values.containsKey("pr_preq_qty") && !values.containsKey("qo_pr_source_requisition_id")) {
            var initial=new LinkedHashMap<>(values);initial.put("pr_preq_qty",141);return super.create(writer,model,initial);
        }
        return super.create(writer,model,values);
    }
    @Override List<Map<String,Object>> initialLines(){
        var peer=new LinkedHashMap<>(candidate("1"));peer.put("erp_entry_id","800000002");return List.of(candidate("140"),peer);
    }
    Map<String,Object> peerLine(){
        var rows=rows("pr_purchase_order_line","pr_pol_order_id",order);
        var peers=rows.stream().filter(row->"800000002".equals(row.get("qo_pr_order_line_erp_entry_id"))).toList();assertEquals(1,peers.size());
        assertEquals(0,BigDecimal.ONE.compareTo(new BigDecimal(peers.getFirst().get("pr_pol_qty").toString())));return peers.getFirst();
    }
    @AfterEach void stopProvider() throws Exception {
        if(erp!=null)erp.stop(0);
        if(privateDirectory!=null && !Files.exists(privateDirectory.resolve("provider-calls.json")))writePrivate("provider-calls.json",providerCalls);
        if(privateDirectory!=null && providerSnapshot!=null)writePrivate("provider-last-view.json",providerSnapshot);
    }

    @Test void formalChangedEventReadsBoundViewAndPersistsAppliedThenConfirmedReview() throws Exception {
        privateDirectory=Path.of(System.getenv("PROCUREMENT_CHANGE_EVIDENCE_DIR"));
        base=System.getenv("PROCUREMENT_CHANGE_PUBLIC_BASE");assertEquals("http://127.0.0.1:6472",base);
        var credentials=json.readTree(Path.of(System.getenv("PROCUREMENT_CHANGE_ADMIN_SESSION")).toFile());admin=credentials.path("data").path("jwt").asText();assertFalse(admin.isBlank());
        assertEquals(Long.toString(tenant),api("GET","/api/auth/me",null,admin).path("data").path("user").path("tenantId").asText());
        initializeProviderAndLineage();
        var application=api("POST","/api/open-platform/applications",Map.of("name",namespace+" change HTTP fixture","description","Synthetic ERP only; actual managed procurement HTTP acceptance"),admin).path("data");
        String applicationPid=application.path("pid").asText();assertFalse(applicationPid.isBlank());
        var installed=api("POST","/api/open-platform/applications/"+applicationPid+"/installations",Map.of("environment","development","scopes",List.of("procurement.orders.write","procurement.requisitions.invalidate","procurement.orders.invalidate"),"rateLimitPerMinute",60),admin).path("data");
        installation=installed.path("pid").asText();assertFalse(installation.isBlank());
        var token=raw("POST","/api/open-platform/installations/"+installation+"/integration-tokens",Map.of("name",namespace,"expirationMode","never"),admin,namespace+"-token",200);
        assertEquals("0",token.path("code").asText());assertEquals("never",token.path("data").path("metadata").path("expirationMode").asText());integrationToken=token.path("data").path("token").asText();assertFalse(integrationToken.isBlank());
        writePrivate("fixture-identities.json",Map.of("tenant",tenant,"owner",actor,"account",namespace,"connectionPid",connection,"taskPid",taskPid,"executionPid",executionPid,"orderPid",order,"linePid",linePid,"installationPid",installation));
        installBoundConfiguration();
        configureReadContract();
        providerSnapshot=snapshot(12,210,"2026-10-20T11:00:00");
        var applied=notifyAndAwait(12,"2026-10-20T03:00:00Z");
        assertEquals("updated",applied.path("result").path("status").asText());assertEquals("12",applied.path("result").path("appliedSourceVersion").asText());assertFalse(applied.path("result").path("newShipmentBlocked").asBoolean());
        verifyNative(applied,210,false,12,"applied");
        create("pr_purchase_order_confirmation_common:create","pr_purchase_order_confirmation_common",Map.of("pr_oc_po_id",order,"pr_oc_supplier_id",supplier,"pr_oc_status","confirmed"));
        providerSnapshot=snapshot(13,80,"2026-10-20T11:05:00");
        var review=notifyAndAwait(13,"2026-10-20T03:05:00Z");
        assertEquals("manual_review_required",review.path("result").path("status").asText());assertEquals("12",review.path("result").path("appliedSourceVersion").asText());assertTrue(review.path("result").path("newShipmentBlocked").asBoolean());
        assertTrue(review.path("result").path("reasonCodes").toString().contains("supplier_confirmed"));verifyNative(review,210,true,13,"review");
        assertEquals(2,providerCalls.stream().filter(x->"login".equals(x.get("kind"))).count());
        assertEquals(2,providerCalls.stream().filter(x->"view".equals(x.get("kind"))).count());
        assertTrue(providerCalls.stream().allMatch(x->Boolean.TRUE.equals(x.get("valid"))));
        var deleted=verifyDeletionNotifications();
        writePrivate("provider-calls.json",providerCalls);writePrivate("completed.json",Map.of("publicHttpExecuted",true,"externalProviderSynthetic",true,"actualErpWrites",0,"applied",applied,"review",review,"providerCalls",providerCalls,"deletions",deleted));
    }

    void initializeProviderAndLineage() throws Exception {
        erp=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        erp.createContext("/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc",exchange->{
            var body=json.readTree(exchange.getRequestBody());boolean valid="POST".equals(exchange.getRequestMethod()) && namespace.equals(body.path("acctID").asText())
                && "synthetic".equals(body.path("username").asText()) && "synthetic".equals(body.path("appId").asText()) && "synthetic-change-secret".equals(body.path("appSecret").asText());
            providerCalls.add(Map.of("kind","login","valid",valid));
            if(valid)exchange.getResponseHeaders().add("Set-Cookie","change-fixture="+namespace+"; Path=/K3Cloud");
            byte[] bytes=json.writeValueAsBytes(Map.of("LoginResultType",valid?1:0));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        erp.createContext("/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.View.common.kdsvc",exchange->{
            var body=json.readTree(exchange.getRequestBody());boolean valid="POST".equals(exchange.getRequestMethod()) && "PUR_PurchaseOrder".equals(body.path("formid").asText())
                && "700000001".equals(body.path("data").path("Id").asText()) && ("change-fixture="+namespace).equals(exchange.getRequestHeaders().getFirst("Cookie"));
            var current=providerSnapshot;providerCalls.add(Map.of("kind","view","valid",valid,"formId",body.path("formid").asText(),"orderId",body.path("data").path("Id").asText(),"sourceVersion",current==null?"missing":current.path("VersionNo").asText()));
            byte[] bytes=json.writeValueAsBytes(Map.of("Result",Map.of("ResponseStatus",Map.of("IsSuccess",valid && current!=null),"Result",current==null?Map.of():current)));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });erp.start();
        tx(SYNC,()->db.update("customer_pcba_int_kingdee_connection",connection,Map.of("customer_pcba_kd_base_url","http://127.0.0.1:"+erp.getAddress().getPort()+"/K3Cloud")));
        var secret=new CloudConfigSaveRequest();secret.setConfigLevel("tenant");secret.setServiceType("integration");secret.setProviderCode(namespace);secret.setEnabled(true);secret.setPriority(1);secret.setConfig("{\"appSecret\":\"synthetic-change-secret\"}");configs.saveConfig(secret);
        Object taskService=construct("ProcurementTaskService",new Class<?>[]{DataAccessor.class},db);
        var task=(Map<?,?>)tx("qo_procurement_task_common:create",()->invoke(taskService,"create",new Class<?>[]{Object.class,String.class,Map.class},tenant,actor,Map.of("title",namespace,"connectionPid",connection)));
        taskPid=task.get("recordPid").toString();
        tx(SYNC,()->db.update("pr_purchase_request",sourcePid,physical("source",Map.of("task_pid",taskPid,"account_id",namespace,"instance_key",connection,"external_system","KINGDEE","material_id","900","requisition_id","100","entry_id","11"))));
        executionPid=create("qo_procurement_execution_common:generate_orders","qo_procurement_execution_common",physical("execution",Map.of("key",namespace+"-execution","task_pid",taskPid,"supplier_pid",supplier,"state","succeeded","erp_order_id","700000001","erp_bill_no",namespace,"revision",0)));
        reference("BD_MATERIAL","900",null,"prod_product",product);
        reference("BD_Supplier","101",null,"pr_supplier_common",supplier);
        reference("PUR_Requisition","100","11","pr_purchase_request",sourcePid);
        var currencies=rows("fin_currency","fin_cur_code","CNY");assertEquals(1,currencies.size(),"Exactly one existing CNY mapping required");
    }
    void reference(String form,String external,String entry,String model,String local){
        Class<?> identityClass=type("ProcurementExternalReferences$Identity");
        Object identity=construct("ProcurementExternalReferences$Identity",new Class<?>[]{String.class,String.class,String.class,String.class,String.class,String.class,String.class},
            Long.toString(tenant),"KINGDEE",connection,namespace,form,external,entry==null?"":entry);
        Object registry=construct("ProcurementExternalReferences",new Class<?>[]{DataAccessor.class},db);
        tx(SYNC,()->{invoke(registry,"bind",new Class<?>[]{identityClass,String.class,String.class,String.class},identity,model,local,namespace);return null;});
    }
    Map<String,Object> contract(){var value=new LinkedHashMap<String,Object>();
        value.put("connectionPid",connection);value.put("accountId",namespace);value.put("formId","PUR_PurchaseOrder");value.put("supplierReferenceFormId","BD_Supplier");value.put("readMode","kingdee-po-view-v1");
        value.put("businessStatusValues",Map.of("C","approved","A","draft","B","pending","D","pending","Z","draft"));value.put("closedStatusValues",Map.of("A",false,"B",true));value.put("cancelledStatusValues",Map.of("A",false,"B",true));
        value.put("maxRows",1000);value.put("progressAuthority","amos-local");value.put("sourceLinkRuleId","Fixture-Requisition-Order");value.put("sourceLinkTable","FixtureReqEntry");value.put("languageId",2052);value.put("currencyValues",Map.of("CUR01","CNY"));value.put("stableCompleteRead",true);return value;
    }
    void configureReadContract() throws Exception {
        var current=scope(PROCESS,()->db.getById("customer_pcba_int_kingdee_connection",connection));
        var response=api("POST","/api/meta/commands/execute/qo_procurement_order_common:configure_change_read",Map.of("targetRecordPid",connection,"expectedVersion",current.get("row_version"),"clientRequestId",namespace+"-configure","payload",Map.of("qo_pr_connection_order_change_contract",Map.of("versionPath","VersionNo","effectiveTimePath","ChangeDate","timeZone","Asia/Shanghai","versionEncoding","zero_padded_decimal"))),admin);
        assertEquals("completed",response.path("data").path("phaseReached").asText());assertEquals("configured",response.path("data").path("data").path("status").asText());
    }
    void installBoundConfiguration() throws Exception {
        Path original=Path.of(System.getenv("PROCUREMENT_QUOTE_CONFIG_SOURCE"));var snapshots=json.createArrayNode();
        var commandFiles=new ArrayList<Path>();try(var stream=Files.list(original.resolve("config/commands"))){commandFiles.addAll(stream.filter(p->p.toString().endsWith(".json")).sorted().toList());}
        for(Path file:commandFiles)for(var command:json.readTree(file.toFile())) {
            var published=api("GET","/api/meta/commands/by-code/"+command.path("code").asText(),null,admin).path("data");
            var snapshot=json.createObjectNode();snapshot.put("tenantId",tenant);snapshot.put("code",command.path("code").asText());snapshot.set("executionConfig",published.get("executionConfig"));snapshots.add(snapshot);
        }
        Path snapshotFile=privateDirectory.resolve("commands-before.json");writePrivate("commands-before.json",snapshots);
        Path destination=privateDirectory.resolve("installed-change-config");
        var process=new ProcessBuilder(System.getenv("PROCUREMENT_FIXTURE_PYTHON"),original.getParent().getParent().resolve("scripts/procurement_bound_import.py").toString(),
            "--source",original.toString(),"--snapshot",snapshotFile.toString(),"--output",destination.toString(),"--tenant",Long.toString(tenant),"--installation",System.getenv("PROCUREMENT_RETAINED_INSTALLATION"),"--connection",System.getenv("PROCUREMENT_RETAINED_CONNECTION"),"--owner",System.getenv("PROCUREMENT_RETAINED_OWNER")).redirectErrorStream(true).redirectOutput(privateDirectory.resolve("prepare-config.log").toFile()).start();
        assertEquals(0,process.waitFor(),"Bound import preparation must preserve current account configuration");
        var changed=new LinkedHashMap<String,JsonNode>();
        try(var files=Files.list(destination.resolve("config/commands"))) {for(Path file:files.filter(p->p.toString().endsWith(".json")).toList()) {
            ArrayNode commands=(ArrayNode)json.readTree(file.toFile());boolean touched=false;
            for(JsonNode command:commands)if(command.path("code").asText().startsWith("qo_procurement_order_notice_common:") || command.path("code").asText().startsWith("qo_procurement_deletion_notice_common:")) {
                var bindings=(ArrayNode)command.path("handlerParams").path("installedIntegration").path("bindings");
                var binding=json.createObjectNode();binding.put("installationPid",installation);binding.put("revision",1);
                Object parameters=command.path("code").asText().startsWith("qo_procurement_order_notice_common:")
                    ?Map.of("connectionPid",connection,"ownerUserPid",actor,"procurementOrderReadContracts",List.of(contract()),"publishBusinessStates",List.of("approved"))
                    :Map.of("connectionPid",connection,"ownerUserPid",actor);
                binding.set("parameters",json.valueToTree(parameters));bindings.add(binding);touched=true;changed.put(command.path("code").asText(),command.path("handlerParams").deepCopy());
            }
            if(touched)json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),commands);
        }}
        assertEquals(9,changed.size());
        var preview=raw("POST","/api/plugins/import/parse-directory",Map.of("path",destination.toString()),admin,null,200);assertTrue(preview.path("valid").asBoolean());
        var result=raw("POST","/api/plugins/import/import-directory-sync",Map.of("path",destination.toString(),"conflictStrategy","OVERWRITE","validateReferences",true,"autoDeployProcesses",false,"autoPublishModels",true,"autoPublishFields",true,"autoPublishCommands",true,"autoPublishPages",true,"createResourcePermissions",false),admin,null,200);assertTrue(result.path("success").asBoolean());
        for(var command:changed.entrySet()) {
            var current=api("GET","/api/meta/commands/by-code/"+command.getKey(),null,admin).path("data");var config=current.path("executionConfig");if(config.isTextual())config=json.readTree(config.asText());assertEquals(command.getValue(),config.path("handlerParams"));
        }
        writePrivate("installed-command-bindings.json",changed);
    }
    ObjectNode snapshot(int version,int quantity,String changedAt) throws Exception {
        ObjectNode view=(ObjectNode)json.readTree("""
            {"Id":700000001,"BillNo":"PLACEHOLDER","SupplierId_Id":101,"SupplierId":{"Id":101},"Date":"2026-10-20T00:00:00","DocumentStatus":"C","CloseStatus":"A","CancelStatus":"A","PurchaseOrgId":{"Number":"ORG-FIXTURE"},
             "POOrderFinance":[{"SettleCurrId":{"Number":"CUR01"},"PayConditionId":{"Number":"NET30"},"ExchangeRate":1,"IsIncludedTax":true}],
             "POOrderEntry":[{"Id":800000001,"MaterialId_Id":900,"MaterialId":{"Id":900,"Number":"PLACEHOLDER","Name":[{"Key":2052,"Value":"Change fixture material"}],"Specification":[{"Key":2052,"Value":""}]},
               "Qty":1,"PriceUnitQty":1,"UnitId":{"Number":"PCS"},"PriceUnitId":{"Number":"PCS"},"BaseUnitId":{"Number":"PCS"},"DeliveryDate":"2026-11-01T00:00:00",
               "Price":1,"TaxPrice":1.13,"TaxRate":13,"Amount":1,"TaxAmount":0.13,"AllAmount":1.13,"ReceiveQty":0,"StockInQty":0,
               "FPOOrderEntry_Link":[{"RuleId":"Fixture-Requisition-Order","STableName":"FixtureReqEntry","SBillId":100,"SId":11,"BaseUnitQty":1}],
               "POOrderEntryDeliPlan":[{"Id":900000001,"PlanQty":1,"BasePlanQty":1,"PlanDeliveryDate":"2026-11-01T00:00:00","PlanUnitID":{"Number":"PCS"},"PlanBaseUnitID":{"Number":"PCS"}}]}]}
            """);
        view.put("BillNo",namespace);view.put("VersionNo",String.format(Locale.ROOT,"%03d",version));view.put("ChangeDate",changedAt);
        ObjectNode line=(ObjectNode)view.path("POOrderEntry").get(0);((ObjectNode)line.path("MaterialId")).put("Number",namespace);
        line.put("Qty",quantity);line.put("PriceUnitQty",quantity);line.put("Amount",quantity);line.put("TaxAmount",new BigDecimal(quantity).multiply(new BigDecimal("0.13")));line.put("AllAmount",new BigDecimal(quantity).multiply(new BigDecimal("1.13")));
        ((ObjectNode)line.path("FPOOrderEntry_Link").get(0)).put("BaseUnitQty",quantity);var plan=(ObjectNode)line.path("POOrderEntryDeliPlan").get(0);plan.put("PlanQty",quantity);plan.put("BasePlanQty",quantity);
        ObjectNode peer=line.deepCopy();peer.put("Id",800000002);peer.put("Qty",1);peer.put("PriceUnitQty",1);peer.put("Amount",1);peer.put("TaxAmount",new BigDecimal("0.13"));peer.put("AllAmount",new BigDecimal("1.13"));
        ((ObjectNode)peer.path("FPOOrderEntry_Link").get(0)).put("BaseUnitQty",1);
        var peerPlan=(ObjectNode)peer.path("POOrderEntryDeliPlan").get(0);peerPlan.put("Id",900000002);peerPlan.put("PlanQty",1);peerPlan.put("BasePlanQty",1);
        ((ArrayNode)view.path("POOrderEntry")).add(peer);return view;
    }
    JsonNode notifyAndAwait(int version,String effective) throws Exception {
        String id=namespace+"-change-"+version;
        var body=Map.of("id",id,"type","order.changed","schemaVersion",1,"occurredAt",effective,"subject",Map.of("type","purchase-order","pid","700000001"),"sequence",version,"data",Map.of("formId","PUR_PurchaseOrder","orderId","700000001","sourceVersion",Integer.toString(version),"changedAt",effective,"operationId",namespace+"-operation-"+version,"changeSource","purchase_order_reapproved"));
        return postAndAwait("kingdee-procurement-orders",id,body);
    }
    JsonNode postAndAwait(String sourceCode,String id,Object body) throws Exception {
        var ack=raw("POST","/api/open/v1/event-sources/"+sourceCode+"/events",body,integrationToken,id,202);assertEquals(id,ack.path("eventId").asText());assertFalse(ack.path("duplicate").asBoolean());
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();JsonNode result;
        do {result=raw("GET","/api/open/v1/event-sources/"+sourceCode+"/events/"+id,null,integrationToken,null,200);
            if("completed".equals(result.path("state").asText()) && "delivered".equals(result.path("deliveryStatus").asText()))break;
            assertNotEquals("failed",result.path("state").asText(),"A failed event must be investigated without new business POST");Thread.sleep(1500);
        }while(System.nanoTime()<deadline);
        assertEquals("completed",result.path("state").asText());assertEquals("delivered",result.path("deliveryStatus").asText());assertEquals(ack.path("recordPid"),result.path("recordPid"));
        try(var c=source.getConnection()) {c.setReadOnly(true);try(var stmt=c.prepareStatement("SELECT r.state,r.request_pid,o.status,(SELECT count(*) FROM ab_integration_receipt x WHERE x.tenant_id=r.tenant_id AND x.event_id=r.durable_event_id AND x.status='applied') FROM ab_installed_integration_receipt r JOIN ab_outbox o ON o.tenant_id=r.tenant_id AND o.event_id=r.durable_event_id WHERE r.tenant_id=? AND r.durable_event_id=?")) {
            stmt.setLong(1,tenant);stmt.setString(2,"ext:"+installation+":"+sourceCode+":"+id);try(var rs=stmt.executeQuery()){assertTrue(rs.next());assertEquals("completed",rs.getString(1));assertEquals(ack.path("recordPid").asText(),rs.getString(2));assertEquals("delivered",rs.getString(3));assertEquals(2,rs.getLong(4));assertFalse(rs.next());}
        }}
        return result;
    }
    Map<String,Object> verifyDeletionNotifications() throws Exception {
        var beforeOrder=scope(PROCESS,()->db.getById("pr_purchase_order",order));var beforeLine=scope(PROCESS,()->db.getById("pr_purchase_order_line",linePid));
        var peerBefore=peerLine();String partialId=namespace+"-partial-delete";
        var partialBody=Map.of("id",partialId,"type","order.deleted","schemaVersion",1,"occurredAt","2026-10-20T03:10:01Z","subject",Map.of("type","purchase-order","pid","700000001"),"sequence",14,
            "data",Map.of("formId","PUR_PurchaseOrder","orderId","700000001","deletionScope","entries","entryIds",List.of("800000001"),"sourceVersion","14","deletedAt","2026-10-20T03:10:00Z","operationId",partialId));
        var partial=postAndAwait("kingdee-procurement-order-deletions",partialId,partialBody);
        assertEquals("external_change_pending",partial.path("result").path("collaborationState").asText());assertTrue(partial.path("result").path("fulfillmentBlocked").asBoolean());assertEquals(1,partial.path("result").path("affectedLineCount").asInt());
        var afterOrder=scope(PROCESS,()->db.getById("pr_purchase_order",order));var afterLine=scope(PROCESS,()->db.getById("pr_purchase_order_line",linePid));
        assertEquals(peerBefore,peerLine());assertNotEquals("deleted",peerLine().get("qo_pr_order_line_external_source_state"));assertEquals("partially_deleted",afterOrder.get("qo_pr_order_external_source_state"));assertEquals("deleted",afterLine.get("qo_pr_order_line_external_source_state"));
        for(String field:List.of("pr_pol_qty","pr_pol_price","pr_pol_amount","qo_pr_order_line_gross_unit_price","qo_pr_order_line_tax_rate","qo_pr_order_line_tax_amount","qo_pr_order_line_gross_amount","qo_pr_order_line_submitted_qty","pr_pol_received_qty"))assertEquals(beforeLine.get(field),afterLine.get(field));
        for(String field:List.of("pr_po_code","pr_po_supplier","pr_po_status","pr_po_currency_code","qo_pr_order_applied_source_version","qo_pr_order_commercial_snapshot"))assertEquals(beforeOrder.get(field),afterOrder.get(field));
        writePrivate("partial-order-delete-native.json",Map.of("result",partial,"orderBefore",beforeOrder,"lineBefore",beforeLine,"order",afterOrder,"line",afterLine,"unselectedPeerLine",peerLine()));
        String aux=create(SYNC,"pr_purchase_request",physical("source",Map.ofEntries(Map.entry("code",namespace+"-aux"),Map.entry("task_pid",taskPid),Map.entry("product_pid",product),Map.entry("requested_qty",10),Map.entry("approved_qty",10),Map.entry("remaining_qty",10),Map.entry("reserved_qty",0),Map.entry("organization","ORG-FIXTURE"),Map.entry("unit","PCS"),Map.entry("state","eligible"),Map.entry("origin","external_requisition"),Map.entry("business_status","pending"),Map.entry("collaboration_managed",true),Map.entry("external_system","KINGDEE"),Map.entry("account_id",namespace),Map.entry("instance_key",connection),Map.entry("requisition_id","101"),Map.entry("entry_id","12"),Map.entry("material_id","900"),Map.entry("revision",0))));
        reference("PUR_Requisition","101","12","pr_purchase_request",aux);
        var auxBefore=scope(PROCESS,()->db.getById("pr_purchase_request",aux));var peerSourceBefore=scope(PROCESS,()->db.getById("pr_purchase_request",sourcePid));
        String fullId=namespace+"-whole-requisition-delete";
        var fullBody=Map.of("id",fullId,"type","requisition.deleted","schemaVersion",1,"occurredAt","2026-10-20T03:15:01Z","subject",Map.of("type","purchase-requisition","pid","101"),"sequence",1,
            "data",Map.of("formId","PUR_Requisition","requisitionId","101","deletionScope","document","sourceVersion","1","deletedAt","2026-10-20T03:15:00Z","operationId",fullId));
        var full=postAndAwait("kingdee-procurement-requisition-deletions",fullId,fullBody);
        assertEquals("invalidated",full.path("result").path("outcome").asText());assertTrue(full.path("result").path("blockedForFurtherProcurement").asBoolean());assertEquals(1,full.path("result").path("affectedCount").asInt());
        assertNotNull(auxBefore.get("pr_preq_product_id"));assertEquals(peerBefore,peerLine());var auxAfter=scope(PROCESS,()->db.getById("pr_purchase_request",aux));assertEquals("external_deleted",auxAfter.get("qo_pr_source_state"));
        for(String field:List.of("pr_preq_qty","qo_pr_source_approved_qty","qo_pr_source_remaining_qty","qo_pr_source_reserved_qty","qo_pr_source_task_pid","pr_preq_product_id"))assertEquals(auxBefore.get(field),auxAfter.get(field));
        assertEquals(peerSourceBefore,scope(PROCESS,()->db.getById("pr_purchase_request",sourcePid)));assertEquals(afterOrder,scope(PROCESS,()->db.getById("pr_purchase_order",order)));assertEquals(afterLine,scope(PROCESS,()->db.getById("pr_purchase_order_line",linePid)));
        writePrivate("whole-requisition-delete-native.json",Map.of("result",full,"sourcePid",aux,"sourceBefore",auxBefore,"source",auxAfter,"peerSource",peerSourceBefore));
        return Map.of("partialOrder",partial,"wholeRequisition",full);
    }
    void verifyNative(JsonNode result,int quantity,boolean pending,int sourceVersion,String phase) throws Exception {
        var header=scope(PROCESS,()->db.getById("pr_purchase_order",order));var line=scope(PROCESS,()->db.getById("pr_purchase_order_line",linePid));
        assertEquals(0,new BigDecimal(quantity).compareTo(new BigDecimal(line.get("pr_pol_qty").toString())));assertEquals(pending,header.get("qo_pr_order_change_pending"));assertEquals(Integer.toString(sourceVersion),header.get("qo_pr_order_source_version"));
        assertEquals(0,new BigDecimal(line.get("qo_pr_order_line_submitted_qty").toString()).signum());assertEquals(0,new BigDecimal(line.get("pr_pol_received_qty").toString()).signum());
        var ledger=scope(PROCESS,()->db.getById("qo_procurement_order_notice_common",result.path("recordPid").asText()));assertEquals("completed",ledger.get("qo_pr_order_notice_state"));
        writePrivate(phase+"-native.json",Map.of("result",result,"order",header,"line",line,"ledger",ledger));
    }
    JsonNode api(String method,String path,Object body,String token) throws Exception {var response=raw(method,path,body,token,null,200);assertEquals("0",response.path("code").asText(),"Management API rejected "+path);return response;}
    JsonNode raw(String method,String path,Object body,String token,String key,int status) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(180)).header("Authorization","Bearer "+token).header("Content-Type","application/json");if(key!=null)request.header("Idempotency-Key",key);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        int number=++requestNumber;writePrivate("http-"+number+"-request.json",Map.of("method",method,"path",path,"body",body==null?Map.of():body));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());JsonNode parsed=json.readTree(response.body());writePrivate("http-"+number+"-response.json",Map.of("status",response.statusCode(),"body",parsed));
        assertEquals(status,response.statusCode(),"HTTP "+method+" "+path);return parsed;
    }
    void writePrivate(String name,Object value) throws Exception {
        Path path=privateDirectory.resolve(name);Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
    }
}
