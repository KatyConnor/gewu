package com.gewu.application.agent.dto;

import lombok.Data;

@Data
public class CreateMcpServerCommand {
    private String name;
    private String description;
    private String transport;
    private String command;
    private String args;
    private String url;
    private String env;
}
