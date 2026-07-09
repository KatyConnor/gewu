package com.gewu.application.session.dto;

import lombok.Data;

@Data
public class UpdateSessionCommand {

    private String title;
    private Integer status;
    private Integer isPublic;
    private String agent;
    private String directory;
}
