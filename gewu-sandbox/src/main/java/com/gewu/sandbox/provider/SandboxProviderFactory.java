package com.gewu.sandbox.provider;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SandboxProviderFactory {

    private final Map<String, SandboxProvider> providers;

    public SandboxProviderFactory(List<SandboxProvider> providerList) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(SandboxProvider::getType, Function.identity()));
    }

    public SandboxProvider getProvider(String type) {
        SandboxProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("不支持的沙箱类型: " + type);
        }
        return provider;
    }

    public SandboxProvider getDefaultProvider() {
        return getProvider("docker");
    }
}
