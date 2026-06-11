package com.lobsterai.skillgateway.config;

import java.util.Arrays;

import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultRoutePlanner;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import com.lobsterai.skillgateway.audit.ContentTypeNormalizingInterceptor;
import com.lobsterai.skillgateway.audit.GatewayHttpClientAuditInterceptor;

@Configuration
public class SkillGatewayHttpClientConfig {

    @Bean
    public RestTemplate gatewayRestTemplate(
            GatewayHttpClientAuditInterceptor auditInterceptor,
            ContentTypeNormalizingInterceptor contentTypeInterceptor
    ) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(20);
        // 显式禁用系统代理（避免 SOCKS 代理连接错误）。
        // JDK 1.8 没有 ProxySelector.of(null)（JDK 9+），使用 DefaultRoutePlanner + null HttpHost
        // 等价于"不通过任何代理直连"。
        HttpRoutePlanner noProxyRoutePlanner = new DefaultRoutePlanner(null);
        org.apache.http.client.HttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setRoutePlanner(noProxyRoutePlanner)
                .build();
        ClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        BufferingClientHttpRequestFactory buffering = new BufferingClientHttpRequestFactory(factory);
        RestTemplate restTemplate = new RestTemplate(buffering);
        restTemplate.setInterceptors(Arrays.asList(contentTypeInterceptor, auditInterceptor));
        return restTemplate;
    }
}
