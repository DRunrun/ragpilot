package com.ragpilot.bootstrap.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.resource.PathResourceResolver;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Admin UI 静态资源与开发期 CORS（ADM-0.4）。
 *
 * <p>链路位置：bootstrap Web 层。{@code /admin/**} 托管 Vue 构建产物；
 * SPA history 回退到 {@code index.html}。
 */
@Configuration
public class AdminWebConfig implements WebFluxConfigurer {

    @Value("${ragpilot.admin.ui-dist:ragpilot-admin-ui/dist}")
    private String uiDist;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = resolveLocation();
        registry.addResourceHandler("/admin", "/admin/", "/admin/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .resourceChain(true)
                .addResolver(new SpaIndexFallbackResolver());
    }

    private String resolveLocation() {
        Path fs = Path.of(uiDist).toAbsolutePath().normalize();
        if (Files.isDirectory(fs)) {
            String uri = fs.toUri().toString();
            return uri.endsWith("/") ? uri : uri + "/";
        }
        // 也可把 dist 拷到 classpath:/static/admin/
        return "classpath:/static/admin/";
    }

    /**
     * 开发期允许本机 Vite 跨域调 API。
     *
     * <p>必须用 origin pattern：Vite 端口被占用时会落到 5174/5175…；
     * 经 proxy 转发时仍会带上浏览器 Origin，只写死 5173 会被 CorsWebFilter 直接 403。
     */
    @Bean
    CorsWebFilter adminCorsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
                "http://127.0.0.1:*",
                "http://localhost:*"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsWebFilter(source);
    }

    /**
     * Vue Router history：找不到静态文件时回退 index.html。
     */
    static final class SpaIndexFallbackResolver extends PathResourceResolver {
        @Override
        protected Mono<Resource> getResource(String resourcePath, Resource location) {
            return super.getResource(resourcePath, location)
                    .switchIfEmpty(Mono.defer(() -> {
                        try {
                            Resource index;
                            if (location instanceof FileSystemResource) {
                                index = location.createRelative("index.html");
                            } else {
                                index = new ClassPathResource("static/admin/index.html");
                            }
                            if (index.exists() && index.isReadable()) {
                                return Mono.just(index);
                            }
                        } catch (IOException ignored) {
                            // fall through
                        }
                        return Mono.empty();
                    }));
        }
    }
}
