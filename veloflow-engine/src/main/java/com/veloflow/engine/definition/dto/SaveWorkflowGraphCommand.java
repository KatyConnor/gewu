package com.veloflow.engine.definition.dto;

import lombok.Data;

import java.util.List;

@Data
public class SaveWorkflowGraphCommand {

    private List<WorkflowNodeDTO> nodes;
    private List<WorkflowTransitionDTO> transitions;
}
