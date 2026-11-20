package dev.codetrace.system;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.codetrace.config.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemInfoController.class)
@ActiveProfiles("api")
@Import(CorrelationIdFilter.class)
class SystemInfoControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SystemInfoService service;

    @Test
    void returnsInfoShape() throws Exception {
        when(service.info()).thenReturn(
                new SystemInfoService.SystemInfo("codetrace", "0.1.0", "api", "1", "UP"));

        mvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("codetrace"))
                .andExpect(jsonPath("$.role").value("api"))
                .andExpect(jsonPath("$.dbMigrationVersion").value("1"))
                .andExpect(jsonPath("$.redis").value("UP"))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void echoesSafeCorrelationId() throws Exception {
        when(service.info()).thenReturn(
                new SystemInfoService.SystemInfo("codetrace", "0.1.0", "api", "1", "UP"));

        mvc.perform(get("/api/v1/system/info").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
    }

    @Test
    void replacesUnsafeCorrelationId() throws Exception {
        when(service.info()).thenReturn(
                new SystemInfoService.SystemInfo("codetrace", "0.1.0", "api", "1", "UP"));

        mvc.perform(get("/api/v1/system/info").header("X-Correlation-Id", "bad id with spaces"))
                .andExpect(header().string("X-Correlation-Id",
                        org.hamcrest.Matchers.not("bad id with spaces")));
    }
}
