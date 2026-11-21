package dev.codetrace.jobs.controller;

import dev.codetrace.jobs.controller.dto.JobResponse;
import dev.codetrace.jobs.service.JobService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Job state. Jobs are created by the feature that needs the work (sync, index, graph, impact). */
@RestController
@Profile("api")
@RequestMapping("/api/v1")
public class JobController {
    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/index-jobs/{jobId}")
    public JobResponse job(@PathVariable UUID jobId) {
        return JobResponse.from(jobs.get(jobId));
    }
}
