package com.squaretoast.admin;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class AdminConfig {

    /** Mapped to all of /api/*; the filter itself picks out the admin paths, so new versions stay covered. */
    @Bean
    FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(JdbcClient db) {
        FilterRegistrationBean<AdminAuthFilter> bean = new FilterRegistrationBean<>(new AdminAuthFilter(db));
        bean.addUrlPatterns("/api/*");
        return bean;
    }
}
