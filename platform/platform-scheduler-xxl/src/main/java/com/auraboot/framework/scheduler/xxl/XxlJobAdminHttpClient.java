package com.auraboot.framework.scheduler.xxl;

import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
public class XxlJobAdminHttpClient implements XxlJobAdminClient {

    static final int SUCCESS_CODE = 200;

    private static final String GLUE_TYPE_BEAN = "BEAN";
    private static final String DEFAULT_AUTHOR = "AuraBoot";
    private static final String DEFAULT_MISFIRE_STRATEGY = "DO_NOTHING";
    private static final String DEFAULT_ROUTE_STRATEGY = "FIRST";
    private static final String DEFAULT_BLOCK_STRATEGY = "SERIAL_EXECUTION";

    private final XxlJobProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private volatile String loginCookie;

    @Override
    public XxlJobAdminResponse upsertJob(XxlJobAdminRequest request) {
        int jobGroupId = ensureJobGroupId(request.getExecutorAppName(), true);
        Integer jobId = findJobId(jobGroupId, request.getTaskPid());

        JsonNode response;
        if (jobId == null) {
            response = postForm("/jobinfo/insert", buildJobForm(request, jobGroupId, null));
            jobId = parseJobId(response);
        } else {
            postForm("/jobinfo/update", buildJobForm(request, jobGroupId, jobId));
        }

        startJob(jobId);

        XxlJobAdminResponse result = new XxlJobAdminResponse();
        result.setSuccess(true);
        result.setExternalJobId(String.valueOf(jobId));
        result.setMessage("Success");
        return result;
    }

    @Override
    public void disableJob(String taskPid) {
        Integer jobGroupId = findJobGroupId(properties.getExecutorAppName());
        if (jobGroupId == null) {
            return;
        }
        Integer jobId = findJobId(jobGroupId, taskPid);
        if (jobId != null) {
            postForm("/jobinfo/stop", idsForm(jobId));
        }
    }

    @Override
    public void deleteJob(String taskPid) {
        Integer jobGroupId = findJobGroupId(properties.getExecutorAppName());
        if (jobGroupId == null) {
            return;
        }
        Integer jobId = findJobId(jobGroupId, taskPid);
        if (jobId != null) {
            postForm("/jobinfo/delete", idsForm(jobId));
        }
    }

    @Override
    public void triggerJob(String taskPid, String executorPayload) {
        Integer jobGroupId = findJobGroupId(properties.getExecutorAppName());
        if (jobGroupId == null) {
            throw new BusinessException("XXL-JOB executor group not found: " + properties.getExecutorAppName());
        }
        Integer jobId = findJobId(jobGroupId, taskPid);
        if (jobId == null) {
            throw new BusinessException("XXL-JOB job not found for AuraBoot task: " + taskPid);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id", String.valueOf(jobId));
        form.add("executorParam", Objects.toString(executorPayload, ""));
        form.add("addressList", "");
        postForm("/jobinfo/trigger", form);
    }

    private int ensureJobGroupId(String executorAppName, boolean createIfMissing) {
        String appName = normalizeExecutorAppName(executorAppName);
        Integer existing = findJobGroupId(appName);
        if (existing != null || !createIfMissing) {
            return existing == null ? -1 : existing;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("appname", appName);
        form.add("title", "AuraBoot " + appName);
        form.add("addressType", "0");
        form.add("addressList", "");
        postForm("/jobgroup/insert", form);

        Integer created = findJobGroupId(appName);
        if (created == null) {
            throw new BusinessException("XXL-JOB executor group was not created: " + appName);
        }
        return created;
    }

    private Integer findJobGroupId(String executorAppName) {
        String appName = normalizeExecutorAppName(executorAppName);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("offset", "0");
        form.add("pagesize", "10");
        form.add("appname", appName);
        form.add("title", "");
        JsonNode response = postForm("/jobgroup/pageList", form);
        for (JsonNode item : response.path("data").path("data")) {
            if (appName.equals(item.path("appname").asText())) {
                return item.path("id").asInt();
            }
        }
        return null;
    }

    private Integer findJobId(int jobGroupId, String taskPid) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("offset", "0");
        form.add("pagesize", "10");
        form.add("jobGroup", String.valueOf(jobGroupId));
        form.add("triggerStatus", "-1");
        form.add("jobDesc", taskPid);
        form.add("executorHandler", XxlJobSchedulerEngine.EXECUTOR_HANDLER);
        form.add("author", "");
        JsonNode response = postForm("/jobinfo/pageList", form);
        for (JsonNode item : response.path("data").path("data")) {
            if (!XxlJobSchedulerEngine.EXECUTOR_HANDLER.equals(item.path("executorHandler").asText())) {
                continue;
            }
            String executorParam = item.path("executorParam").asText("");
            String jobDesc = item.path("jobDesc").asText("");
            if (executorParam.contains("\"taskPid\":\"" + taskPid + "\"") || jobDesc.contains(taskPid)) {
                return item.path("id").asInt();
            }
        }
        return null;
    }

    private MultiValueMap<String, String> buildJobForm(XxlJobAdminRequest request, int jobGroupId, Integer jobId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (jobId != null) {
            form.add("id", String.valueOf(jobId));
        }
        form.add("jobGroup", String.valueOf(jobGroupId));
        form.add("jobDesc", buildJobDesc(request));
        form.add("author", DEFAULT_AUTHOR);
        form.add("alarmEmail", "");
        form.add("scheduleType", requireText(request.getScheduleType(), "scheduleType"));
        form.add("scheduleConf", requireText(request.getScheduleConf(), "scheduleConf"));
        form.add("misfireStrategy", DEFAULT_MISFIRE_STRATEGY);
        form.add("executorRouteStrategy", DEFAULT_ROUTE_STRATEGY);
        form.add("executorHandler", requireText(request.getExecutorHandler(), "executorHandler"));
        form.add("executorParam", Objects.toString(request.getExecutorPayload(), ""));
        form.add("executorBlockStrategy", DEFAULT_BLOCK_STRATEGY);
        form.add("executorTimeout", String.valueOf(toTimeoutSeconds(request.getTimeoutMs())));
        form.add("executorFailRetryCount", String.valueOf(Math.max(0, Objects.requireNonNullElse(request.getMaxRetries(), 0))));
        form.add("glueType", GLUE_TYPE_BEAN);
        form.add("glueSource", "");
        form.add("glueRemark", "Initialized by AuraBoot");
        form.add("childJobId", "");
        return form;
    }

    private MultiValueMap<String, String> idsForm(int jobId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("ids[]", String.valueOf(jobId));
        return form;
    }

    private void startJob(int jobId) {
        postForm("/jobinfo/start", idsForm(jobId));
    }

    private JsonNode postForm(String path, MultiValueMap<String, String> form) {
        ensureLoggedIn();
        return postForm(path, form, true);
    }

    private synchronized void ensureLoggedIn() {
        if (loginCookie != null && !loginCookie.isBlank()) {
            return;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("userName", properties.getAdminUsername());
        form.add("password", properties.getAdminPassword());
        form.add("ifRemember", "on");

        JsonNode response = postForm("/auth/doLogin", form, false);
        requireSuccess("/auth/doLogin", response);
    }

    private JsonNode postForm(String path, MultiValueMap<String, String> form, boolean includeCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (includeCookie && loginCookie != null && !loginCookie.isBlank()) {
            headers.add(HttpHeaders.COOKIE, loginCookie);
        }

        ResponseEntity<String> responseEntity;
        try {
            responseEntity = restTemplate.postForEntity(adminBaseUrl() + path, new HttpEntity<>(form, headers), String.class);
        } catch (RestClientException e) {
            throw new BusinessException("XXL-JOB Admin request failed: " + path + " - " + e.getMessage());
        }

        if (!includeCookie) {
            loginCookie = extractCookie(responseEntity.getHeaders());
        }

        JsonNode response = parseResponse(path, responseEntity.getBody());
        requireSuccess(path, response);
        return response;
    }

    private JsonNode parseResponse(String path, String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new BusinessException("XXL-JOB Admin response is not JSON for " + path + ": " + e.getOriginalMessage());
        }
    }

    private void requireSuccess(String path, JsonNode response) {
        int code = response.path("code").asInt();
        if (code != SUCCESS_CODE) {
            String message = response.path("msg").asText("unknown error");
            throw new BusinessException("XXL-JOB Admin request failed: " + path + " - " + message);
        }
    }

    private String extractCookie(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null || setCookies.isEmpty()) {
            throw new BusinessException("XXL-JOB Admin login succeeded but no login cookie was returned");
        }
        StringBuilder cookie = new StringBuilder();
        for (String setCookie : setCookies) {
            if (setCookie == null || setCookie.isBlank()) {
                continue;
            }
            if (!cookie.isEmpty()) {
                cookie.append("; ");
            }
            cookie.append(setCookie.split(";", 2)[0]);
        }
        if (cookie.isEmpty()) {
            throw new BusinessException("XXL-JOB Admin login cookie is empty");
        }
        return cookie.toString();
    }

    private int parseJobId(JsonNode response) {
        String rawId = response.path("data").asText("");
        try {
            return Integer.parseInt(rawId);
        } catch (NumberFormatException e) {
            throw new BusinessException("XXL-JOB Admin insert did not return a valid job id: " + rawId);
        }
    }

    private String buildJobDesc(XxlJobAdminRequest request) {
        String desc = "AuraBoot:" + request.getTaskPid() + ":" + Objects.toString(request.getJobName(), "");
        return desc.length() <= 255 ? desc : desc.substring(0, 255);
    }

    private int toTimeoutSeconds(Long timeoutMs) {
        if (timeoutMs == null || timeoutMs <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (timeoutMs + 999) / 1000);
    }

    private String adminBaseUrl() {
        String addresses = properties.getAdminAddresses();
        if (addresses == null || addresses.isBlank()) {
            throw new BusinessException("XXL-JOB Admin address is not configured");
        }
        String firstAddress = addresses.split(",", 2)[0].trim();
        while (firstAddress.endsWith("/")) {
            firstAddress = firstAddress.substring(0, firstAddress.length() - 1);
        }
        return firstAddress;
    }

    private String normalizeExecutorAppName(String appName) {
        String normalized = appName == null || appName.isBlank() ? properties.getExecutorAppName() : appName;
        if (normalized == null || normalized.isBlank()) {
            throw new BusinessException("XXL-JOB executor app name is not configured");
        }
        return normalized.trim();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("XXL-JOB Admin request missing " + fieldName);
        }
        return value;
    }
}
