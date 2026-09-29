package com.auraboot.framework.user.util;

import java.util.Locale;

public final class EmailAddressNormalizer {

    private EmailAddressNormalizer() {
    }

    public static String normalize(String email) {
        if (email == null || email.isBlank()) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
