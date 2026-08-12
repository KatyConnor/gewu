package com.gewu.interfaceconfig.security;

import com.gewu.common.crypto.PasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Spring Security PasswordEncoder 适配 — 底层使用 SM3 加盐多轮迭代.
 *
 * <p>与 {@link com.gewu.common.crypto.PasswordHasher} 保持一致，
 * 存储格式: {iterations}${salt}${hash}。
 */
public class Sm3PasswordEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
        return PasswordHasher.hash(rawPassword.toString());
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        return PasswordHasher.verify(rawPassword.toString(), encodedPassword);
    }
}
