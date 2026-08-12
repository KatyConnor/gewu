package com.gewu.application.project.dto;

import lombok.Data;

@Data
public class CreateDocumentCommand {

    private String docName;
    private String docType;
    private String content;
}
