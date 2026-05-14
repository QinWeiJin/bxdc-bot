package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.audit.ContentTypeNormalizingInterceptor;
import com.lobsterai.skillgateway.audit.GatewayHttpClientAuditInterceptor;
import com.lobsterai.skillgateway.audit.HttpClientAuditContext;
import com.lobsterai.skillgateway.audit.HttpClientAuditMode;
import com.lobsterai.skillgateway.http.OutboundUrlNormalizer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * API 代理服务：封装 {@link RestTemplate}，供 Skill 对外 HTTP 与内部 HTTP 调用共用。
 */
@Service
public class ApiProxyService {

    private final RestTemplate gatewayRestTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayHttpClientAuditInterceptor auditInterceptor;
    private final ContentTypeNormalizingInterceptor contentTypeInterceptor;

    public ApiProxyService(
            RestTemplate gatewayRestTemplate,
            ObjectMapper objectMapper,
            GatewayHttpClientAuditInterceptor auditInterceptor,
            ContentTypeNormalizingInterceptor contentTypeInterceptor
    ) {
        this.gatewayRestTemplate = gatewayRestTemplate;
        this.objectMapper = objectMapper;
        this.auditInterceptor = auditInterceptor;
        this.contentTypeInterceptor = contentTypeInterceptor;
    }

    public Object callApi(String url, String method, Map<String, ?> headers, Object body) {
        return callApi(url, method, headers, body, HttpClientAuditMode.NONE);
    }

    public Object callApi(
            String url,
            String method,
            Map<String, ?> headers,
            Object body,
            HttpClientAuditMode mode
    ) {
            HttpClientAuditContext.set(mode);
        try {
            String outboundUrl = OutboundUrlNormalizer.normalizeForOutboundHttp(url);
            HttpHeaders httpHeaders = new HttpHeaders();
            applyOutboundHeaders(httpHeaders, headers);
            HttpEntity<Object> entity = new HttpEntity<>(body, httpHeaders);
            ResponseEntity<String> response = gatewayRestTemplate.exchange(
                    outboundUrl,
                    HttpMethod.valueOf(method.toUpperCase()),
                    entity,
                    String.class
            );
            return parseResponseBody(response);
        } finally {
            HttpClientAuditContext.clear();
        }
    }

    public Object callApi(String url, String method, Map<String, ?> headers, Object body, int timeoutSeconds) {
        return callApi(url, method, headers, body, HttpClientAuditMode.NONE, timeoutSeconds);
    }

    public Object callApi(
            String url,
            String method,
            Map<String, ?> headers,
            Object body,
            HttpClientAuditMode mode,
            int timeoutSeconds
    ) {
        HttpClientAuditContext.set(mode);
        try {
            String outboundUrl = OutboundUrlNormalizer.normalizeForOutboundHttp(url);
            HttpHeaders httpHeaders = new HttpHeaders();
            applyOutboundHeaders(httpHeaders, headers);
            HttpEntity<Object> entity = new HttpEntity<>(body, httpHeaders);

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(timeoutSeconds * 1000);
            factory.setReadTimeout(timeoutSeconds * 1000);
            BufferingClientHttpRequestFactory bufferingFactory = new BufferingClientHttpRequestFactory(factory);
            RestTemplate timedTemplate = new RestTemplate(bufferingFactory);
            timedTemplate.setInterceptors(List.of(contentTypeInterceptor, auditInterceptor));

            ResponseEntity<String> response = timedTemplate.exchange(
                    outboundUrl,
                    HttpMethod.valueOf(method.toUpperCase()),
                    entity,
                    String.class
            );
            return parseResponseBody(response);
        } finally {
            HttpClientAuditContext.clear();
        }
    }

    /**
     * {@link SkillController.ApiRequest#getHeaders()} may use string values or JSON arrays (single or multi),
     * matching OpenAPI / exported configs; each becomes one or more {@code HttpHeaders#add} calls.
     */
    static void applyOutboundHeaders(HttpHeaders target, Map<String, ?> headers) {
        if (headers == null || target == null) {
            return;
        }
        for (Map.Entry<String, ?> e : headers.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String name = e.getKey();
            addHeaderValue(target, name, e.getValue());
        }
    }

    private static void addHeaderValue(HttpHeaders target, String name, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String) {
            target.add(name, (String) value);
        } else if (value instanceof Collection) {
            @SuppressWarnings("unchecked")
            Collection<Object> c = (Collection<Object>) value;
            for (Object o : c) {
                if (o != null) {
                    target.add(name, o.toString());
                }
            }
        } else if (value.getClass().isArray()) {
            int n = Array.getLength(value);
            for (int i = 0; i < n; i++) {
                Object o = Array.get(value, i);
                if (o != null) {
                    target.add(name, o.toString());
                }
            }
        } else {
            target.add(name, value.toString());
        }
    }

    private Object parseResponseBody(ResponseEntity<String> response) {
        String responseBody = response.getBody();
        if (responseBody == null) {
            return null;
        }
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && (
                MediaType.APPLICATION_JSON.includes(contentType)
                        || contentType.getSubtype().toLowerCase().contains("json")
        )) {
            try {
                return objectMapper.readValue(responseBody, Object.class);
            } catch (Exception ignored) {
            }
        }
        return responseBody;
    }
}
