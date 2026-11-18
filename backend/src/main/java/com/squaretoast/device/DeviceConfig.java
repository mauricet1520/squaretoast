package com.squaretoast.device;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class DeviceConfig {

    /** Mapped to all of /api/*; the filter itself decides which paths are guarded, so new versions stay covered. */
    @Bean
    FilterRegistrationBean<DeviceAuthFilter> deviceAuthFilter(JdbcClient db) {
        FilterRegistrationBean<DeviceAuthFilter> bean = new FilterRegistrationBean<>(new DeviceAuthFilter(db));
        bean.addUrlPatterns("/api/*");
        return bean;
    }
}
