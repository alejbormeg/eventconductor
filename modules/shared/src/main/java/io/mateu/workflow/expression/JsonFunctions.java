package io.mateu.workflow.expression;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.jexl3.introspection.JexlPermissions;

/** The deliberately small JSON namespace available to sandboxed expressions. */
public final class JsonFunctions {
    public static final JexlPermissions PERMISSIONS = new JexlPermissions.ClassPermissions(
            JexlPermissions.RESTRICTED, JsonFunctions.class);
    public static final java.util.Map<String, Object> NAMESPACES = java.util.Map.of("json", new JsonFunctions());
    private static final ObjectMapper JSON = new ObjectMapper();

    public Object parse(String text) {
        if (text == null) return null;
        try {
            return JSON.readValue(text, Object.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid JSON", error);
        }
    }
}
