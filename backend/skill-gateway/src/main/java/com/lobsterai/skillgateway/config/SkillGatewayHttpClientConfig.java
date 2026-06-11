package com.lobsterai.skillgateway.config;

import com.lobsterai.skillgateway.audit.ContentTypeNormalizingInterceptor;
import com.lobsterai.skillgateway.audit.GatewayHttpClientAuditInterceptor;
import org.apache.http.HttpHost;
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

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

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
        // Explicitly disable proxy to avoid SOCKS proxy connection errors
        HttpRoutePlanner noProxyRoutePlanner = new SystemDefaultRoutePlanner(new java.net.ProxySelector() {
            public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                return java.util.Collections.singletonList(java.net.Proxy.NO_PROXY);
            }
            public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {}
        });
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
