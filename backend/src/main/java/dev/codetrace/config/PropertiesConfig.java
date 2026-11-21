package dev.codetrace.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({IngestionProperties.class, GraphProperties.class, ImpactProperties.class})
public class PropertiesConfig {
}
