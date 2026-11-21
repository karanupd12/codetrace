package dev.codetrace.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} methods, such as the worker's job sweeper. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
