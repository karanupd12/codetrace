package dev.codetrace.graph.controller.dto;

import dev.codetrace.jobs.controller.dto.JobResponse;

public record BuildResponse(SnapshotResponse snapshot, JobResponse job) {
}
