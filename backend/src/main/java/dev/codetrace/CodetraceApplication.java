package dev.codetrace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Single entry point. Runtime role is chosen with the Spring profile: "api" or "worker". */
@SpringBootApplication
public class CodetraceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodetraceApplication.class, args);
    }
}
