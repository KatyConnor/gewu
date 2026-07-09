package com.gewu.sandbox.provider;

import com.gewu.domain.sandbox.Sandbox;
import com.gewu.sandbox.dto.CreateSandboxCommand;
import com.gewu.sandbox.dto.ExecCommandResponse;

public interface SandboxProvider {

    Sandbox create(CreateSandboxCommand command);

    void start(Sandbox sandbox);

    void stop(Sandbox sandbox);

    void destroy(Sandbox sandbox);

    ExecCommandResponse exec(Sandbox sandbox, String command, Integer timeoutSeconds);

    String getType();
}
