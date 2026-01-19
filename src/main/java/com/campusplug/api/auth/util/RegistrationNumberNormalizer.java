package com.campusplug.api.auth.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RegistrationNumberNormalizer {

    // Accepts:
    // - 2023/BIT/216
    // - 2023/BIT/216/PS
    // - 2023BIT216
    // - 2023BIT216PS
    private static final Pattern CANONICAL_SLASHED = Pattern.compile(
            "^(?<year>\\d{4})\\s*/\\s*(?<program>[A-Za-z]{2,10})\\s*/\\s*(?<num>\\d{1,4})(?:\\s*/\\s*(?<ps>PS))?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMPACT = Pattern.compile(
            "^(?<year>\\d{4})(?<program>[A-Za-z]{2,10})(?<num>\\d{1,4})(?<ps>PS)?$",
            Pattern.CASE_INSENSITIVE);

    private RegistrationNumberNormalizer() {
    }

    public static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("registrationNumber is required");
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("registrationNumber is required");
        }

        Matcher slashed = CANONICAL_SLASHED.matcher(trimmed);
        if (slashed.matches()) {
            return toCanonical(
                    slashed.group("year"),
                    slashed.group("program"),
                    slashed.group("num"),
                    slashed.group("ps"));
        }

        String compactCandidate = trimmed.replace("/", "").replace(" ", "");
        Matcher compact = COMPACT.matcher(compactCandidate);
        if (compact.matches()) {
            return toCanonical(
                    compact.group("year"),
                    compact.group("program"),
                    compact.group("num"),
                    compact.group("ps"));
        }

        throw new IllegalArgumentException("Invalid registrationNumber format. Expected YYYY/PROGRAM/NNN(/PS)?");
    }

    private static String toCanonical(String year, String program, String num, String ps) {
        String normalizedProgram = program.toUpperCase(Locale.ROOT);

        String digits = num;
        if (digits.length() < 3) {
            digits = "0".repeat(3 - digits.length()) + digits;
        }

        String canonical = year + "/" + normalizedProgram + "/" + digits;
        if (ps != null && !ps.isBlank()) {
            canonical += "/PS";
        }
        return canonical;
    }
}
