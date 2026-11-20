package dev.codetrace.config;

import java.util.Arrays;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(RoleProperties.class)
public class WebConfig implements WebMvcConfigurer {
    private final RoleProperties props;

    public WebConfig(RoleProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = props.corsOrigins() == null ? "" : props.corsOrigins();
        String[] allowed = Arrays.stream(origins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (allowed.length == 0) {
            return;
        }
        registry.addMapping("/api/**").allowedOrigins(allowed).allowedMethods("GET", "POST", "OPTIONS");
    }
}
