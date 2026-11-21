package dev.codetrace.source.controller.dto;

import dev.codetrace.source.model.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRepositoryRequest(@NotNull SourceType sourceType,
                                        @NotBlank @Size(max = 2048) String location,
                                        @Size(max = 100) String displayName) {
}
