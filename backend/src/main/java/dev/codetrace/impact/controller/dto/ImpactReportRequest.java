package dev.codetrace.impact.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ImpactReportRequest(@NotBlank @Size(max = 64) String baseCommit, @NotBlank @Size(max = 64) String headCommit,
                                  @Min(1) @Max(20) Integer depth) {
}
