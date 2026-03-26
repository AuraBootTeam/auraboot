package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.service.PasswordPolicyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class PasswordPolicyServiceImpl implements PasswordPolicyService {

    @Value("${security.password.min-length:8}")
    private int minLength;

    @Value("${security.password.max-length:128}")
    private int maxLength;

    @Value("${security.password.require-uppercase:true}")
    private boolean requireUppercase;

    @Value("${security.password.require-lowercase:true}")
    private boolean requireLowercase;

    @Value("${security.password.require-digit:true}")
    private boolean requireDigit;

    @Value("${security.password.require-special:false}")
    private boolean requireSpecial;

    /** Common weak passwords that pass complexity rules but are easily guessed */
    private static final Set<String> COMMON_WEAK_PASSWORDS = Set.of(
            "password1", "password123", "password1!", "qwerty123", "qwerty1234",
            "admin123", "admin@123", "admin1234", "letmein123", "welcome1",
            "welcome123", "changeme1", "changeme123", "abc12345", "abcd1234",
            "iloveyou1", "p@ssw0rd", "p@ssword1", "passw0rd", "passw0rd!",
            "qwer1234", "asdf1234", "zxcv1234", "1q2w3e4r", "1qaz2wsx",
            "test1234", "test12345", "master123", "dragon123", "monkey123",
            "shadow123", "sunshine1", "trustno1", "princess1", "football1",
            "baseball1", "michael1", "login123", "starwars1", "access123"
    );

    @Override
    public List<String> validate(String password) {
        List<String> errors = new ArrayList<>();

        // Handle null/empty upfront — fail fast with clear message
        if (password == null || password.isEmpty()) {
            errors.add("Password cannot be empty");
            return errors;
        }

        if (password.length() < minLength) {
            errors.add("Password must be at least " + minLength + " characters");
        }
        if (password.length() > maxLength) {
            errors.add("Password must be at most " + maxLength + " characters");
        }
        if (requireUppercase && !password.chars().anyMatch(Character::isUpperCase)) {
            errors.add("Password must contain at least one uppercase letter");
        }
        if (requireLowercase && !password.chars().anyMatch(Character::isLowerCase)) {
            errors.add("Password must contain at least one lowercase letter");
        }
        if (requireDigit && !password.chars().anyMatch(Character::isDigit)) {
            errors.add("Password must contain at least one digit");
        }
        if (requireSpecial && !password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) {
            errors.add("Password must contain at least one special character");
        }

        // Check against common weak passwords (case-insensitive)
        if (COMMON_WEAK_PASSWORDS.contains(password.toLowerCase())) {
            errors.add("Password is too common and easily guessable");
        }

        return errors;
    }
}
