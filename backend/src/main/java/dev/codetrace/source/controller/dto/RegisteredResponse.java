package dev.codetrace.source.controller.dto;

import dev.codetrace.jobs.controller.dto.JobResponse;

public record RegisteredResponse(SourceRepositoryResponse repository, JobResponse syncJob) {
}
