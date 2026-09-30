package com.example.testinglearning.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns on {@code @PreAuthorize}/{@code @PostAuthorize}. It's a separate class from
 * {@link SecurityConfig} so {@code OrderServiceMethodSecuritySliceTest} can load method security on
 * its own, with no web layer or filter chain.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
