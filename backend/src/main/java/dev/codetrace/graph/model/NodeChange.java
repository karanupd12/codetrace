package dev.codetrace.graph.model;

import java.util.UUID;

/**
 * A node present in only one of two snapshots (ADDED, REMOVED) or in both with different modifiers or subtype
 * (CHANGED). The node ids locate it in each snapshot; one of them is null unless it is CHANGED.
 */
public record NodeChange(String change, String nodeType, String qualifiedName, String signature, String path,
                         UUID baseNodeId, UUID headNodeId, String baseSubtype, String headSubtype, String baseModifiers,
                         String headModifiers) {
}
