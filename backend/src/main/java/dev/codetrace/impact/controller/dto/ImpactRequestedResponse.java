package dev.codetrace.impact.controller.dto;

import dev.codetrace.jobs.controller.dto.JobResponse;

public record ImpactRequestedResponse(ImpactReportResponse report, JobResponse job) {
}
