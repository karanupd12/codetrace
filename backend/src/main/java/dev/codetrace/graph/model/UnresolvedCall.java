package dev.codetrace.graph.model;

import java.util.UUID;

/** A call whose receiver type the resolver could not determine: the calling node and the method name and arity sought. */
public record UnresolvedCall(UUID callerId, String callerName, String path, int line, String methodName, int argCount) {
}
