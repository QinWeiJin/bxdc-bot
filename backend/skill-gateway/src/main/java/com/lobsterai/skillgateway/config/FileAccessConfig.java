package com.lobsterai.skillgateway.config;

import com.lobsterai.skillgateway.util.AamTokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Web MVC 配置。
 * <p>
 * 注册文件 API 权限拦截器，确保所有 /api/files/** 请求带有 X-User-Id 头，
 * 并将 userId 存储为 request attribute 供 Controller 使用。
 * </p>
 */
@Configuration
public class FileAccessConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new FileAccessInterceptor())
                .addPathPatterns("/api/files/**")
                .excludePathPatterns("/api/files/health", "/api/files/tools/health", "/api/files/download/**");
    }

    /**
     * 文件 API 访问拦截器。
     * <p>
     * 前置拦截：校验 X-User-Id 请求头是否存在，并存入 request attribute。
     * </p>
     */
    public static class FileAccessInterceptor implements HandlerInterceptor {

        private static final Logger log = LoggerFactory.getLogger(FileAccessInterceptor.class);

        /** 将 userId 存入 request attribute 的键名，Controller 通过此键获取 */
        public static final String REQUEST_USER_ID_ATTR = "fileRequestUserId";

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String userId = AamTokenUtil.extractUserId(request);
            if (userId == null) {
                log.warn("Missing X-User-Id header for file API: {} {}", request.getMethod(), request.getRequestURI());
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"Missing required header: X-User-Id\"}");
                return false;
            }
            // 将 userId 存入 request attribute，供 Controller 获取
            request.setAttribute(REQUEST_USER_ID_ATTR, userId);
            return true;
        }
    }
}
