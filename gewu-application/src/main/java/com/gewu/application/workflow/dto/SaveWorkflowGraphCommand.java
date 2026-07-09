package com.gewu.application.workflow.dto;

import lombok.Data;

import java.util.List;

@Data
public class SaveWorkflowGraphCommand {

    private List<WorkflowNodeDTO> nodes;
    private List<WorkflowTransitionDTO> transitions;
}
