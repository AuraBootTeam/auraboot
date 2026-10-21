package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** One Native JVM's synthetic read-only ERP protocol; never a customer/provider acceptance claim. */
final class ProcurementQuoteLifecycleErp implements AutoCloseable {
    static final String ROOT="/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.";
    static final String LOGIN=ROOT+"AuthService.LoginByAppSecret.common.kdsvc";
    static final String QUERY=ROOT+"DynamicFormService.ExecuteBillQuery.common.kdsvc";
    static final String VIEW=ROOT+"DynamicFormService.View.common.kdsvc";
    static final String FIELDS="FSupplierId,FNumber,FName,FDocumentStatus,FForbidStatus,FUseOrgId.FNumber";
    static final String SECRET="controlled-lifecycle-local-secret";
    static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    final AtomicInteger logins=new AtomicInteger(),queries=new AtomicInteger(),views=new AtomicInteger(),denied=new AtomicInteger();
    private final String account;
    private HttpServer server;
    record Reply(int status,JsonNode body,boolean cookie) {}

    ProcurementQuoteLifecycleErp(String account) {this.account=Objects.requireNonNull(account);}
    void start() throws IOException {
        if(server!=null) throw new IllegalStateException("Provider already started");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            Reply reply;
            try {
                byte[] bytes=exchange.getRequestBody().readNBytes(131073);
                require(bytes.length<=131072 && "POST".equals(exchange.getRequestMethod()));
                reply=reply(exchange.getRequestURI().getPath(),JSON.readTree(bytes),exchange.getRequestHeaders().getFirst("Cookie"));
            } catch(IllegalArgumentException | IOException invalid) {
                denied.incrementAndGet();reply=new Reply(400,JSON.createObjectNode().put("error","CONTROLLED_PROTOCOL_REJECTED"),false);
            }
            byte[] body=JSON.writeValueAsBytes(reply.body());
            if(reply.cookie()) exchange.getResponseHeaders().add("Set-Cookie","lifecycle=authenticated; Path=/K3Cloud");
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(reply.status(),body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
    }
    String baseUrl() {if(server==null) throw new IllegalStateException("Provider not started");return "http://127.0.0.1:"+server.getAddress().getPort();}
    Reply reply(String path,JsonNode body,String cookie) throws IOException {
        require(body!=null && body.isObject());
        if(LOGIN.equals(path)) {
            keys(body,Set.of("acctID","username","appId","appSecret","lcid"));
            require(account.equals(body.path("acctID").asText()) && account.equals(body.path("username").asText())
                    && account.equals(body.path("appId").asText()) && SECRET.equals(body.path("appSecret").asText())
                    && body.path("lcid").isIntegralNumber() && body.path("lcid").intValue()==2052);
            logins.incrementAndGet();return new Reply(200,JSON.createObjectNode().put("LoginResultType",1),true);
        }
        require("lifecycle=authenticated".equals(cookie));
        if(QUERY.equals(path)) {
            keys(body,Set.of("data"));var data=body.path("data");
            keys(data,Set.of("FormId","FieldKeys","FilterString","OrderString","StartRow","Limit","TopRowCount"));
            require("BD_Supplier".equals(data.path("FormId").asText()) && FIELDS.equals(data.path("FieldKeys").asText())
                    && "FSupplierId=701".equals(data.path("FilterString").asText()) && "FSupplierId ASC".equals(data.path("OrderString").asText()));
            for(String key:List.of("StartRow","Limit","TopRowCount")) require(data.path(key).isIntegralNumber());
            require(data.path("StartRow").intValue()==0 && data.path("Limit").intValue()==100 && data.path("TopRowCount").intValue()==0);
            queries.incrementAndGet();return new Reply(200,JSON.valueToTree(List.of(List.of(701,"CONTROLLED-SUPPLIER","Controlled lifecycle supplier","C","A","CONTROLLED-ORG"))),false);
        }
        require(VIEW.equals(path));keys(body,Set.of("formid","data"));keys(body.path("data"),Set.of("Id"));
        require(body.path("data").path("Id").isTextual());String id=body.path("data").path("Id").textValue();
        JsonNode value;
        if("BD_Supplier".equals(body.path("formid").asText())) {require("701".equals(id));value=supplier();}
        else {require("BD_PaymentCondition".equals(body.path("formid").asText()) && Set.of("801","802").contains(id));value=condition(id);}
        views.incrementAndGet();var result=JSON.createObjectNode();var envelope=result.putObject("Result");
        envelope.putObject("ResponseStatus").put("IsSuccess",true);envelope.set("Result",value);return new Reply(200,result,false);
    }
    static void keys(JsonNode value,Set<String> expected) {
        require(value!=null && value.isObject());var keys=new HashSet<String>();value.fieldNames().forEachRemaining(keys::add);require(keys.equals(expected));
    }
    private static void require(boolean condition) {if(!condition) throw new IllegalArgumentException("CONTROLLED_PROTOCOL_REJECTED");}
    static ObjectNode supplier() throws IOException {
        var value=(ObjectNode)JSON.readTree(SUPPLIER_TEMPLATE);value.put("Id",701).put("Number","CONTROLLED-SUPPLIER").put("DocumentStatus","C").put("ForbidStatus","A");
        value.putArray("Name").addObject().put("Key",2052).put("Value","Controlled lifecycle supplier");
        var contact=value.putArray("SupplierContact").addObject();contact.put("Id",901).put("Contact","Controlled lifecycle contact")
                .put("Mobile","13900000071").put("EMail","").put("FPost","Controlled role").put("ConForbidStatus","A");
        ((ObjectNode)value.path("UseOrgId")).put("Number","CONTROLLED-ORG");
        var finance=(ArrayNode)value.path("SupplierFinance");var first=(ObjectNode)finance.get(0);
        first.put("Id",1101).put("PayCondition_Id",801);((ObjectNode)first.path("PayCondition")).put("Id",801).put("Number","CONTROLLED-801");
        var second=first.deepCopy();second.put("Id",1102).put("PayCondition_Id",802);((ObjectNode)second.path("PayCondition")).put("Id",802).put("Number","CONTROLLED-802");finance.add(second);
        return value;
    }
    static ObjectNode condition(String id) throws IOException {
        require(Set.of("801","802").contains(id));var value=(ObjectNode)JSON.readTree(CONDITION_TEMPLATE);
        value.put("Id",Integer.parseInt(id)).put("Number","CONTROLLED-"+id);value.putArray("Name").addObject().put("Key",2052).put("Value","Controlled payment "+id);
        var rules=(ArrayNode)value.path("PayRecCondtionEntry");var first=(ObjectNode)rules.get(0);
        first.put("Id",Integer.parseInt(id)*10+1).put("Seq",1).put("Rate",new BigDecimal("40.00000000000000000000000000001"));
        var second=first.deepCopy();second.put("Id",Integer.parseInt(id)*10+2).put("Seq",2).put("Rate",new BigDecimal("59.99999999999999999999999999999"));rules.add(second);
        return value;
    }
    Map<String,Integer> counts(){return Map.of("login",logins.get(),"supplierQuery",queries.get(),"masterViews",views.get(),"rejected",denied.get());}
    @Override public void close(){if(server!=null){server.stop(0);server=null;}}
    // Fully synthetic typed templates, derived from existing payment-reader hermetic fixtures.
    private static final String SUPPLIER_TEMPLATE="""
{
  "Id": 1,
  "CreateOrgId_Id": 1,
  "CreateOrgId": {
    "Id": 1,
    "MultiLanguageText": [
      {
        "LocaleId": 1,
        "Name": "x",
        "PkId": 1
      }
    ],
    "Name": [
      {
        "Key": 1,
        "Value": "x"
      }
    ],
    "Number": "x"
  },
  "UseOrgId_Id": 1,
  "UseOrgId": {
    "Id": 1,
    "MultiLanguageText": [
      {
        "LocaleId": 1,
        "Name": "x",
        "PkId": 1
      }
    ],
    "Name": [
      {
        "Key": 1,
        "Value": "x"
      }
    ],
    "Number": "x"
  },
  "SupplierFinance": [
    {
      "ChargeId": {
        "Id": 1,
        "MultiLanguageText": [
          {
            "LocaleId": 1,
            "Name": "x",
            "PkId": 1
          }
        ],
        "Name": [
          {
            "Key": 1,
            "Value": "x"
          }
        ],
        "Number": "x",
        "msterID": 1
      },
      "ChargeId_Id": 1,
      "CustomerId": null,
      "CustomerId_Id": 0,
      "Description": "x",
      "FTaxType": {
        "FDataValue": [
          {
            "Key": 1,
            "Value": "x"
          }
        ],
        "FNumber": "x",
        "Id": "x",
        "MultiLanguageText": [
          {
            "FDataValue": "x",
            "LocaleId": 1,
            "PkId": "x"
          }
        ]
      },
      "FTaxType_Id": "x",
      "Id": 1,
      "InvoiceType": "x",
      "PayAdvanceAmount": 1,
      "PayCondition": {
        "Id": 1,
        "MultiLanguageText": [
          {
            "LocaleId": 1,
            "Name": "x",
            "PkId": 1
          }
        ],
        "Name": [
          {
            "Key": 1,
            "Value": "x"
          }
        ],
        "Number": "x"
      },
      "PayCondition_Id": 1,
      "PayCurrencyId": {
        "AmountDigits": 1,
        "FormatOrder": "x",
        "Id": 1,
        "IsShowCSymbol": true,
        "MultiLanguageText": [
          {
            "LocaleId": 1,
            "Name": "x",
            "PkId": 1
          }
        ],
        "Name": [
          {
            "Key": 1,
            "Value": "x"
          }
        ],
        "Number": "x",
        "PriceDigits": 1,
        "RoundType": "x",
        "Sysmbol": "x",
        "msterID": 1
      },
      "PayCurrencyId_Id": 1,
      "SettleId": {
        "Id": 1,
        "MultiLanguageText": [
          {
            "LocaleId": 1,
            "Name": "x",
            "PkId": 1
          }
        ],
        "Name": [
          {
            "Key": 1,
            "Value": "x"
          }
        ],
        "Number": "x",
        "msterID": 1
      },
      "SettleId_Id": 1,
      "TaxRateId": {
        "Id": 1,
        "MultiLanguageText": [
          {
            "LocaleId": 1,
            "Name": "x",
            "PkId": 1
          }
        ],
        "Name": [
          {
            "Key": 1,
            "Value": "x"
          }
        ],
        "Number": "x",
        "TaxRate": 1,
        "msterID": 1
      },
      "TaxRateId_Id": 1,
      "TendType": null,
      "TendType_Id": ""
    }
  ]
}
""";
    private static final String CONDITION_TEMPLATE="""
{
  "AccountTimeJudge": "x",
  "CreateOrgId": {
    "Id": 1,
    "MultiLanguageText": [
      {
        "LocaleId": 1,
        "Name": "x",
        "PkId": 1
      }
    ],
    "Name": [
      {
        "Key": 1,
        "Value": "x"
      }
    ],
    "Number": "x"
  },
  "CreateOrgId_Id": 1,
  "Description": [
    {
      "Key": 1,
      "Value": "x"
    }
  ],
  "DocumentStatus": "C",
  "FISMATCHBYRATE": true,
  "FPAYMENTMETHOD": "x",
  "ForbidStatus": "A",
  "ISINSTALLMENT": true,
  "Id": 1,
  "IsSysPreset": true,
  "MultiLanguageText": [
    {
      "Description": "x",
      "LocaleId": 1,
      "Name": "x",
      "PkId": 1
    }
  ],
  "MutDueStd": "x",
  "Name": [
    {
      "Key": 1,
      "Value": "x"
    }
  ],
  "Number": "x",
  "PayRecCondtionEntry": [
    {
      "Amount": 1,
      "DueCalMethodDesc": [
        {
          "Key": 1,
          "Value": "x"
        }
      ],
      "DuecalMethodID": {
        "Id": 1,
        "MultiLanguageText": [
          {
            "LocaleId": 1,
            "Name": "x",
            "PkId": 1
          }
        ],
        "Name": [
          {
            "Key": 1,
            "Value": "x"
          }
        ],
        "Number": "x"
      },
      "DuecalMethodID_Id": 1,
      "FWorkCalID": null,
      "FWorkCalID_Id": 0,
      "FendDateBegin": "x",
      "ISMONTHEND": true,
      "ISPREPAID": true,
      "Id": 1,
      "MultiLanguageText": [
        {
          "DueCalMethodDesc": "x",
          "LocaleId": 1,
          "PkId": 1
        }
      ],
      "NEXTPERIOD1": "x",
      "NEXTPERIOD2": "x",
      "NEXTPERIOD3": "x",
      "NEXTPERIOD4": "x",
      "ODConfirmMethod": "x",
      "ODDay": 1,
      "ODDayToEndMonth": 1,
      "ODFIXEDDAYFOURBEG": 1,
      "ODFIXEDDAYFOUREND": 1,
      "ODFIXEDDAYONEBEG": 1,
      "ODFIXEDDAYONEEND": 1,
      "ODFIXEDDAYTHREEBEG": 1,
      "ODFIXEDDAYTHREEEND": 1,
      "ODFIXEDDAYTWOBEG": 1,
      "ODFIXEDDAYTWOEND": 1,
      "ODFixedDayFour": 1,
      "ODFixedDayMatchWay": "x",
      "ODFixedDayOne": 1,
      "ODFixedDayThree": 1,
      "ODFixedDayTwo": 1,
      "ODMonth": 1,
      "Rate": 1,
      "Seq": 1,
      "THISMONTHEND": 1
    }
  ],
  "UseOrgId": {
    "Id": 1,
    "MultiLanguageText": [
      {
        "LocaleId": 1,
        "Name": "x",
        "PkId": 1
      }
    ],
    "Name": [
      {
        "Key": 1,
        "Value": "x"
      }
    ],
    "Number": "x"
  },
  "UseOrgId_Id": 1
}
""";
}
