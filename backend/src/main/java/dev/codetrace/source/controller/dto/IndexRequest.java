package dev.codetrace.source.controller.dto;

import jakarta.validation.constraints.Size;

public record IndexRequest(@Size(max = 64) String commitHash) {
}
