package com.gewu.application.session.dto;

import lombok.Data;

@Data
public class CreateChatSessionCommand {

    private String agentId;
    private String title;
}
