package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded master-data setup only. The independently managed order provider owns every Save. */
final class ProcurementControlledOrderMasterErp implements AutoCloseable {
    private final String account;
    private HttpServer server;
    private int login,query,view,rejected;
    ProcurementControlledOrderMasterErp(String account){this.account=Objects.requireNonNull(account);}
    void start() throws java.io.IOException {
        if(server!=null)throw new IllegalStateException("Already started");server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            JsonNode response;int status=200;
            try {
                if(!"POST".equals(exchange.getRequestMethod()))throw new IllegalArgumentException();
                byte[] bytes=exchange.getRequestBody().readNBytes(131073);if(bytes.length>131072)throw new IllegalArgumentException();
                String path=exchange.getRequestURI().getPath();var body=ProcurementQuoteLifecycleErp.JSON.readTree(bytes);
                response=reply(path,body,exchange.getRequestHeaders().getFirst("Cookie"));
                if(path.equals(ProcurementQuoteLifecycleErp.LOGIN))exchange.getResponseHeaders().add("Set-Cookie","order-master=authenticated; Path=/K3Cloud");
            } catch(Exception invalid){rejected++;status=400;response=ProcurementQuoteLifecycleErp.JSON.createObjectNode().put("error","BOUNDED_MASTER_PROTOCOL_REJECTED");}
            byte[] bytes=ProcurementQuoteLifecycleErp.JSON.writeValueAsBytes(response);exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
    }
    String baseUrl(){if(server==null)throw new IllegalStateException("Not started");return "http://127.0.0.1:"+server.getAddress().getPort();}
    JsonNode reply(String path,JsonNode body,String cookie) throws Exception {
        if(path.equals(ProcurementQuoteLifecycleErp.LOGIN)) {
            ProcurementQuoteLifecycleErp.keys(body,Set.of("acctID","username","appId","appSecret","lcid"));
            require(account.equals(body.path("acctID").asText())&&"local-reader".equals(body.path("username").asText())&&"local-app".equals(body.path("appId").asText())&&"local-test-secret".equals(body.path("appSecret").asText())&&body.path("lcid").isIntegralNumber()&&body.path("lcid").intValue()==2052);
            login++;return ProcurementQuoteLifecycleErp.JSON.createObjectNode().put("LoginResultType",1);
        }
        require("order-master=authenticated".equals(cookie));
        if(path.equals(ProcurementQuoteLifecycleErp.QUERY)) {
            ProcurementQuoteLifecycleErp.keys(body,Set.of("data"));var q=body.path("data");
            ProcurementQuoteLifecycleErp.keys(q,Set.of("FormId","FieldKeys","FilterString","OrderString","StartRow","Limit","TopRowCount"));
            require("BD_Supplier".equals(q.path("FormId").asText())&&ProcurementQuoteLifecycleErp.FIELDS.equals(q.path("FieldKeys").asText())&&"FSupplierId ASC".equals(q.path("OrderString").asText()));
            for(String key:List.of("StartRow","Limit","TopRowCount"))require(q.path(key).isIntegralNumber());
            require(q.path("StartRow").intValue()==0&&q.path("Limit").intValue()==100&&q.path("TopRowCount").intValue()==0);
            String filter=q.path("FilterString").asText();require(Set.of("FSupplierId=701","FSupplierId=701 OR FSupplierId=702").contains(filter));
            var rows=new ArrayList<List<Object>>();for(String id:filter.contains("702")?List.of("701","702"):List.of("701"))rows.add(List.of(Integer.parseInt(id),"CONTROLLED-SUPPLIER-"+id,"Controlled supplier "+id,"C","A","CONTROLLED-ORG"));
            query++;return ProcurementQuoteLifecycleErp.JSON.valueToTree(rows);
        }
        require(path.equals(ProcurementQuoteLifecycleErp.VIEW));ProcurementQuoteLifecycleErp.keys(body,Set.of("formid","data"));ProcurementQuoteLifecycleErp.keys(body.path("data"),Set.of("Id"));require(body.path("data").path("Id").isTextual());
        String id=body.path("data").path("Id").asText();JsonNode result;
        if("BD_Supplier".equals(body.path("formid").asText())) {require(Set.of("701","702").contains(id));result=supplier(id);}
        else {require("BD_PaymentCondition".equals(body.path("formid").asText())&&Set.of("801","802").contains(id));result=ProcurementQuoteLifecycleErp.condition(id);}
        view++;var response=ProcurementQuoteLifecycleErp.JSON.createObjectNode();var data=response.putObject("Result");data.putObject("ResponseStatus").put("IsSuccess",true);data.set("Result",result);return response;
    }
    static ObjectNode supplier(String id) throws java.io.IOException {
        var supplier=ProcurementQuoteLifecycleErp.supplier();supplier.put("Id",Integer.parseInt(id)).put("Number","CONTROLLED-SUPPLIER-"+id);
        ((ObjectNode)supplier.path("SupplierContact").get(0)).put("Id",Integer.parseInt(id)+200).put("Mobile","701".equals(id)?"13900000071":"13900000072");return supplier;
    }
    Map<String,Integer> counts(){return Map.of("login",login,"query",query,"view",view,"rejected",rejected);}
    private static void require(boolean valid){if(!valid)throw new IllegalArgumentException("BOUNDED_MASTER_PROTOCOL_REJECTED");}
    public void close(){if(server!=null)server.stop(0);}
}
