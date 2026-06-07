package com.auraboot.framework.scheduler.xxl;

import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class XxlJobAdminHttpClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private XxlJobProperties properties;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        properties = new XxlJobProperties();
        properties.setAdminAddresses("http://localhost:18080/xxl-job-admin");
        properties.setAdminUsername("admin");
        properties.setAdminPassword("123456");
        properties.setExecutorAppName("auraboot-platform");
    }

    @Test
    void upsertJob_createsGroupInsertsJobAndStartsIt() {
        XxlJobAdminHttpClient client = new XxlJobAdminHttpClient(properties, restTemplate, new ObjectMapper());
        XxlJobAdminRequest request = cronRequest();

        expectLogin();
        expectPost("/jobgroup/pageList", formContains("appname=auraboot-platform"))
                .andRespond(withSuccess(successPage("[]", 0), MediaType.APPLICATION_JSON));
        expectPost("/jobgroup/insert", formContains(
                "appname=auraboot-platform",
                "title=AuraBoot+auraboot-platform",
                "addressType=0"
        )).andRespond(withSuccess(success(), MediaType.APPLICATION_JSON));
        expectPost("/jobgroup/pageList", formContains("appname=auraboot-platform"))
                .andRespond(withSuccess(successPage("""
                        [{"id":11,"appname":"auraboot-platform","title":"AuraBoot auraboot-platform"}]
                        """, 1), MediaType.APPLICATION_JSON));
        expectPost("/jobinfo/pageList", formContains(
                "jobGroup=11",
                "jobDesc=task-1",
                "executorHandler=aurabootScheduledTaskJob"
        )).andRespond(withSuccess(successPage("[]", 0), MediaType.APPLICATION_JSON));
        expectPost("/jobinfo/insert", formContains(
                "jobGroup=11",
                "jobDesc=AuraBoot%3Atask-1%3ANightly+Cleanup",
                "scheduleType=CRON",
                "scheduleConf=0+0+2+*+*+%3F+*",
                "executorHandler=aurabootScheduledTaskJob",
                "executorFailRetryCount=2",
                "executorTimeout=300"
        )).andRespond(withSuccess("""
                {"code":200,"msg":"Success","data":"501"}
                """, MediaType.APPLICATION_JSON));
        expectPost("/jobinfo/start", formContains("ids%5B%5D=501"))
                .andRespond(withSuccess(success(), MediaType.APPLICATION_JSON));

        XxlJobAdminResponse response = client.upsertJob(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getExternalJobId()).isEqualTo("501");
        server.verify();
    }

    @Test
    void upsertJob_updatesExistingJobAndStartsIt() {
        XxlJobAdminHttpClient client = new XxlJobAdminHttpClient(properties, restTemplate, new ObjectMapper());
        XxlJobAdminRequest request = cronRequest();

        expectLogin();
        expectPost("/jobgroup/pageList", formContains("appname=auraboot-platform"))
                .andRespond(withSuccess(successPage("""
                        [{"id":11,"appname":"auraboot-platform","title":"AuraBoot auraboot-platform"}]
                        """, 1), MediaType.APPLICATION_JSON));
        expectPost("/jobinfo/pageList", formContains("jobGroup=11", "jobDesc=task-1"))
                .andRespond(withSuccess(successPage("""
                        [{"id":501,"jobDesc":"AuraBoot:task-1:Nightly Cleanup","executorHandler":"aurabootScheduledTaskJob","executorParam":"{\\"taskPid\\":\\"task-1\\"}"}]
                        """, 1), MediaType.APPLICATION_JSON));
        expectPost("/jobinfo/update", formContains("id=501", "scheduleConf=0+0+2+*+*+%3F+*"))
                .andRespond(withSuccess(success(), MediaType.APPLICATION_JSON));
        expectPost("/jobinfo/start", formContains("ids%5B%5D=501"))
                .andRespond(withSuccess(success(), MediaType.APPLICATION_JSON));

        XxlJobAdminResponse response = client.upsertJob(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getExternalJobId()).isEqualTo("501");
        server.verify();
    }

    @Test
    void triggerJob_findsExternalJobAndPostsExecutorPayload() {
        XxlJobAdminHttpClient client = new XxlJobAdminHttpClient(properties, restTemplate, new ObjectMapper());

        expectLogin();
        expectPost("/jobgroup/pageList", formContains("appname=auraboot-platform"))
                .andRespond(withSuccess(successPage("""
                        [{"id":11,"appname":"auraboot-platform","title":"AuraBoot auraboot-platform"}]
                        """, 1), MediaType.APPLICATION_JSON));
        expectPost("/jobinfo/pageList", formContains("jobGroup=11", "jobDesc=task-1"))
                .andRespond(withSuccess(successPage("""
                        [{"id":501,"jobDesc":"AuraBoot:task-1:Nightly Cleanup","executorHandler":"aurabootScheduledTaskJob","executorParam":"{\\"taskPid\\":\\"task-1\\"}"}]
                        """, 1), MediaType.APPLICATION_JSON));
        expectPost("/jobinfo/trigger", formContains(
                "id=501",
                "executorParam=%7B%22taskPid%22%3A%22task-1%22%2C%22triggerType%22%3A%22manual%22%7D",
                "addressList="
        )).andRespond(withSuccess(success(), MediaType.APPLICATION_JSON));

        client.triggerJob("task-1", "{\"taskPid\":\"task-1\",\"triggerType\":\"manual\"}");

        server.verify();
    }

    @Test
    void upsertJob_adminFailureThrowsBusinessException() {
        XxlJobAdminHttpClient client = new XxlJobAdminHttpClient(properties, restTemplate, new ObjectMapper());

        expectLogin();
        expectPost("/jobgroup/pageList", formContains("appname=auraboot-platform"))
                .andRespond(withSuccess("""
                        {"code":500,"msg":"database unavailable","data":null}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.upsertJob(cronRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("database unavailable");
        server.verify();
    }

    private XxlJobAdminRequest cronRequest() {
        XxlJobAdminRequest request = new XxlJobAdminRequest();
        request.setTaskPid("task-1");
        request.setTenantId(7L);
        request.setJobName("Nightly Cleanup");
        request.setTaskType("cron");
        request.setScheduleType("CRON");
        request.setScheduleConf("0 0 2 * * ? *");
        request.setExecutorAppName("auraboot-platform");
        request.setExecutorHandler("aurabootScheduledTaskJob");
        request.setExecutorPayload("{\"taskPid\":\"task-1\",\"triggerType\":\"scheduled\"}");
        request.setMaxRetries(2);
        request.setTimeoutMs(300000L);
        return request;
    }

    private void expectLogin() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, "xxl_job_login_token=test-cookie; Path=/xxl-job-admin; HttpOnly");
        server.expect(requestTo("http://localhost:18080/xxl-job-admin/auth/doLogin"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(formContains("userName=admin", "password=123456"))
                .andRespond(withSuccess("""
                        {"code":200,"msg":"Success","data":null}
                        """, MediaType.APPLICATION_JSON).headers(headers));
    }

    private org.springframework.test.web.client.ResponseActions expectPost(String path, RequestMatcher bodyMatcher) {
        return server.expect(requestTo("http://localhost:18080/xxl-job-admin" + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.COOKIE, containsString("xxl_job_login_token=test-cookie")))
                .andExpect(bodyMatcher);
    }

    private RequestMatcher formContains(String... fragments) {
        return request -> {
            for (String fragment : fragments) {
                content().string(containsString(fragment)).match(request);
            }
        };
    }

    private String success() {
        return "{\"code\":200,\"msg\":\"Success\",\"data\":null}";
    }

    private String successPage(String rows, int total) {
        return """
                {"code":200,"msg":"Success","data":{"total":%d,"data":%s}}
                """.formatted(total, rows);
    }
}
