package com.example.ajs.auth.config;

import com.example.ajs.auth.repository.AppRepository;
import com.example.ajs.auth.web.AppPermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the per-user app denylist enforcement.
 *
 * <p>A {@link WebMvcConfigurer} is used rather than adding the check to each
 * controller because the permission is a property of the route, not of the
 * handler. Centralising it means a newly added dashboard is governed by adding
 * one map entry, and cannot be forgotten in a controller.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AppRepository apps;

    public WebMvcConfig(AppRepository apps) {
        this.apps = apps;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AppPermissionInterceptor(apps))
                // Scoped to the dashboard routes explicitly. Sign-in, admin and
                // actuator must never be subject to the denylist, or a
                // misconfiguration could lock everyone out of the login page.
                //
                // Every path here must also appear in AppPermissionInterceptor's
                // ROUTE_TO_SLUG map, and in AuthBootstrap.BUILT_IN_APPS. A route
                // missing from THIS list is not governed at all, silently — the
                // timeline was in that state, so it was the one dashboard no
                // administrator could deny. Adding a dashboard means adding it in
                // all three places; the enforcement test covers the timeline.
                .addPathPatterns("/overview", "/overview/**",
                        "/calendar", "/calendar/**",
                        "/timeline", "/timeline/**",
                        "/failures", "/failures/**",
                        "/jobs", "/jobs/**",
                        "/attention", "/attention/**");
    }
}
