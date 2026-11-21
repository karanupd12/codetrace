package dev.codetrace.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** JSON for values stored in JSONB columns. Failures are programming or data errors, so they are unchecked. */
@Component
public class JsonCodec {
    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise stored data.", e);
        }
    }

    /** {@code null} JSON gives {@code fallback}. */
    public <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null) return fallback;
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored data is unreadable.", e);
        }
    }
}
