package com.gewu.application.project.dto;

import lombok.Data;

@Data
public class UpdateDocumentContentCommand {

    private String content;
    private String changeSummary;
}
