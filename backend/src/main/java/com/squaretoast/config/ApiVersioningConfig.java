package com.squaretoast.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Path-segment API versioning: every SquareToast {@code @RestController} is served under {@code /api/v{n}/...}.
 * Path (not header) versioning because browser EventSource (SSE) cannot send custom headers.
 */
@Configuration(proxyBeanMethods = false)
class ApiVersioningConfig implements WebMvcConfigurer {

    static final String API_PREFIX = "/api/{version}";
    static final int VERSION_SEGMENT = 1;

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forBasePackage("com.squaretoast")
                .and(HandlerTypePredicate.forAnnotation(RestController.class)));
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.usePathSegment(VERSION_SEGMENT).addSupportedVersions("1");
    }
}
