package com.gewu.common.crypto;

import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 密码安全策略 — 等保2.0 三级要求：长度不少于 8 位、至少包含四种字符类中的三种、禁止包含用户名和常见弱口令.
 */
@Component
public class PasswordPolicy {

    @Value("${gewu.security.password.min-length:8}")
    private int minLength;

    @Value("${gewu.security.password.require-uppercase:true}")
    private boolean requireUppercase;

    @Value("${gewu.security.password.require-lowercase:true}")
    private boolean requireLowercase;

    @Value("${gewu.security.password.require-digit:true}")
    private boolean requireDigit;

    @Value("${gewu.security.password.require-special:true}")
    private boolean requireSpecial;

    private static final int MAX_LENGTH = 128;

    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?~`]");

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "12345678", "qwerty123", "admin123", "password123",
            "123456789", "1234567", "11111111", "1234567890", "abc12345",
            "p@ssw0rd", "iloveyou", "monkey123", "sunshine", "letmein123",
            "welcome1", "admin@123", "qwerty12", "changeme", "welcome123",
            "zaq12wsx", "1qaz2wsx", "qazwsx123", "trustno1", "abc123"
    );

    public void validate(String password) {
        validate(password, null);
    }

    public void validate(String password, String username) {
        if (password == null || password.length() < minLength) {
            throw BusinessException.of(ResultCode.PASSWORD_POLICY_VIOLATION,
                    "密码长度不能少于" + minLength + "位");
        }
        if (password.length() > MAX_LENGTH) {
            throw BusinessException.of(ResultCode.PASSWORD_POLICY_VIOLATION,
                    "密码长度不能超过" + MAX_LENGTH + "位");
        }

        int categoryCount = countCategories(password);
        if (categoryCount < 3) {
            throw BusinessException.of(ResultCode.PASSWORD_POLICY_VIOLATION,
                    "密码需包含大写字母、小写字母、数字、特殊字符中的至少三种");
        }

        validateRequiredRules(password);

        if (username != null && !username.isEmpty()
                && password.toLowerCase().contains(username.toLowerCase())) {
            throw BusinessException.of(ResultCode.PASSWORD_POLICY_VIOLATION, "密码不能包含用户名");
        }

        if (COMMON_PASSWORDS.contains(password.toLowerCase())) {
            throw BusinessException.of(ResultCode.PASSWORD_TOO_COMMON, "密码过于简单，请更换为更复杂的密码");
        }
    }

    private int countCategories(String password) {
        int count = 0;
        if (UPPERCASE.matcher(password).find()) count++;
        if (LOWERCASE.matcher(password).find()) count++;
        if (DIGIT.matcher(password).find()) count++;
        if (SPECIAL.matcher(password).find()) count++;
        return count;
    }

    private void validateRequiredRules(String password) {
        if (requireUppercase && !UPPERCASE.matcher(password).find()) {
            throw BusinessException.of(ResultCode.PASSWORD_POLICY_VIOLATION, "密码必须包含大写字母");
        }
        if (requireLowercase && !LOWERCASE.matcher(password).find()) {
            throw BusinessException.of(ResultCode.PASSWORD_POLICY_VIOLATION, "密码必须包含小写字母");
        }
        if (requireDigit && !DIGIT.matcher(password).find()) {
            throw BusinessException.of(ResultCode.PASSWORD_POLICY_VIOLATION, "密码必须包含数字");
        }
        if (requireSpecial && !SPECIAL.matcher(password).find()) {
            throw BusinessException.of(ResultCode.PASSWORD_POLICY_VIOLATION, "密码必须包含特殊字符");
        }
    }
}