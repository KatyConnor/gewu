package com.gewu.application.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ContextCompressor {

    private static final int MAX_COMPRESSED_LENGTH = 4000;

    public String compress(List<MessageView> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageView msg = messages.get(i);
            String entry = "[" + msg.role() + "] " + msg.content() + "\n";
            if (sb.length() + entry.length() > MAX_COMPRESSED_LENGTH) {
                break;
            }
            sb.insert(0, entry);
        }

        if (sb.length() > MAX_COMPRESSED_LENGTH) {
            return sb.substring(sb.length() - MAX_COMPRESSED_LENGTH);
        }
        return sb.toString();
    }

    public record MessageView(String role, String content) {
    }
}
