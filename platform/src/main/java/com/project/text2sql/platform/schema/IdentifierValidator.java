package com.project.text2sql.platform.schema;

import java.util.regex.Pattern;

public final class IdentifierValidator {
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private IdentifierValidator() {
    }

    public static void requireValidIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()){
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + fieldName);
        }
    }
}