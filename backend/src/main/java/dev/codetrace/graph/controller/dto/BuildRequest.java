package dev.codetrace.graph.controller.dto;

import jakarta.validation.constraints.Size;

public record BuildRequest(@Size(max = 64) String commitHash) {
}
