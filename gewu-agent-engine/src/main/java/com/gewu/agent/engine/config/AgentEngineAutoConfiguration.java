package com.gewu.agent.engine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.budget.BudgetController;
import com.gewu.agent.engine.cognition.ArbiterEngine;
import com.gewu.agent.engine.cognition.ConfidenceGate;
import com.gewu.agent.engine.cognition.ComplexityRouter;
import com.gewu.agent.engine.cognition.DualSystemRouter;
import com.gewu.agent.engine.cognition.EvolutionHook;
import com.gewu.agent.engine.cognition.NoOpArbiterEngine;
import com.gewu.agent.engine.cognition.NoOpPerceptionEngine;
import com.gewu.agent.engine.cognition.PerceptionEngine;
import com.gewu.agent.engine.orchestration.AgentLifecycleManager;
import com.gewu.agent.engine.orchestration.AntiRunawayGuard;
import com.gewu.agent.engine.scenario.ScenarioAdapterRegistry;
import com.gewu.agent.engine.spi.PolicyService;
import com.gewu.agent.engine.spi.ResponseCache;
import com.gewu.agent.engine.spi.TraceService;
import com.gewu.agent.engine.spi.MetricService;
import com.gewu.agent.engine.spi.ModelSelector;
import com.gewu.agent.engine.tool.security.PromptInjectionDetector;
import com.gewu.agent.engine.tool.security.OutputSanitizer;
import com.gewu.agent.engine.tool.security.SecurityCheck;
import com.gewu.agent.engine.cognition.NoOpEvolutionHook;
import com.gewu.agent.engine.cognition.NoOpReasoningKernel;
import com.gewu.agent.engine.cognition.NoOpReflectionEngine;
import com.gewu.agent.engine.cognition.ReasoningKernel;
import com.gewu.agent.engine.cognition.ReflectionEngine;
import com.gewu.agent.engine.core.AgentEngine;
import com.gewu.agent.engine.core.AgentEngineConfig;
import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.ReactAgentExecutor;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.hitl.NoOpHitlGateway;
import com.gewu.agent.engine.llm.LlmClient;
import com.gewu.agent.engine.llm.LlmClientRegistry;
import com.gewu.agent.engine.llm.LlmProvider;
import com.gewu.agent.engine.llm.LlmRequestBodyBuilder;
import com.gewu.agent.engine.message.DefaultMessageBuilder;
import com.gewu.agent.engine.message.MessageBuilder;
import com.gewu.agent.engine.message.SystemPromptComposer;
import com.gewu.agent.engine.mcp.McpServerConfigSource;
import com.gewu.agent.engine.mcp.McpServerManager;
import com.gewu.agent.engine.mcp.NoOpMcpServerConfigSource;
import com.gewu.agent.engine.contract.ArtifactValidator;
import com.gewu.agent.engine.orchestration.ConflictResolver;
import com.gewu.agent.engine.verification.DualLoopVerifier;
import com.gewu.agent.engine.orchestration.AutonomousExecutor;
import com.gewu.agent.engine.orchestration.DefaultGoalPlanner;
import com.gewu.agent.engine.orchestration.GoalPlanner;
import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.agent.engine.orchestration.Orchestrator;
import com.gewu.agent.engine.orchestration.role.NoOpRoleConfigSource;
import com.gewu.agent.engine.orchestration.role.RoleConfigSource;
import com.gewu.agent.engine.orchestration.role.RoleRegistry;
import com.gewu.agent.engine.memory.MemoryRouter;
import com.gewu.agent.engine.memory.MemoryStore;
import com.gewu.agent.engine.memory.NoOpMemoryRouter;
import com.gewu.agent.engine.memory.NoOpMemoryStore;
import com.gewu.agent.engine.spi.ApiKeyDecryptor;
import com.gewu.agent.engine.spi.AuditService;
import com.gewu.agent.engine.spi.PermissionService;
import com.gewu.agent.engine.spi.PersistenceService;
import com.gewu.agent.engine.spi.SandboxExecutor;
import com.gewu.agent.engine.spi.SessionContextService;
import com.gewu.agent.engine.spi.defaults.NoOpApiKeyDecryptor;
import com.gewu.agent.engine.spi.defaults.NoOpAuditService;
import com.gewu.agent.engine.spi.defaults.NoOpLlmProvider;
import com.gewu.agent.engine.spi.defaults.NoOpModelSelector;
import com.gewu.agent.engine.spi.defaults.NoOpPermissionService;
import com.gewu.agent.engine.spi.defaults.NoOpPersistenceService;
import com.gewu.agent.engine.spi.defaults.NoOpSandboxExecutor;
import com.gewu.agent.engine.spi.defaults.NoOpSessionContextService;
import com.gewu.agent.engine.tool.NoOpToolConfigSource;
import com.gewu.agent.engine.tool.Tool;
import com.gewu.agent.engine.tool.ToolConfigSource;
import com.gewu.agent.engine.tool.ToolExecutor;
import com.gewu.agent.engine.tool.ToolRegistry;
import com.gewu.agent.engine.tool.security.CodeScanner;
import com.gewu.agent.engine.tool.security.CodeScannerCheck;
import com.gewu.agent.engine.tool.security.DefaultCodeScanner;
import com.gewu.agent.engine.tool.security.OutputSanitizer;
import com.gewu.agent.engine.tool.security.PromptInjectionDetector;
import com.gewu.agent.engine.tool.security.SchemaValidator;
import com.gewu.agent.engine.tool.security.SecurityChain;
import com.gewu.agent.engine.tool.security.SecurityCheck;
import com.gewu.agent.engine.tool.security.SsrfValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Agent 引擎自动配置。
 * <p>装配 LLM 客户端注册中心、工具执行器、消息构建器、ReAct 执行器、Agent 引擎门面，
 * 以及全部 SPI 的 NoOp 默认实现。使用方按需实现 SPI 接口注册为 Spring Bean 即可覆盖默认行为。
 *
 * @since 1.0.0
 */
@Configuration
@EnableConfigurationProperties(AgentEngineProperties.class)
public class AgentEngineAutoConfiguration {

    // ==================== 基础设施 ====================

    @Bean
    @ConditionalOnMissingBean(name = "llmHttpClient")
    public HttpClient llmHttpClient(AgentEngineProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(props.getLlm().getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean("agentEngineLlmRequestBodyBuilder")
    @ConditionalOnMissingBean
    public LlmRequestBodyBuilder agentEngineLlmRequestBodyBuilder(ObjectMapper objectMapper) {
        return new LlmRequestBodyBuilder(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmProvider llmProvider() {
        return new NoOpLlmProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmClientRegistry llmClientRegistry(List<LlmClient> clients, LlmProvider provider,
                                                ObjectMapper objectMapper, HttpClient llmHttpClient,
                                                LlmRequestBodyBuilder bodyBuilder,
                                                AgentEngineProperties props) {
        LlmClientRegistry registry = new LlmClientRegistry(clients, provider, objectMapper,
                llmHttpClient, bodyBuilder);
        registry.setRequestTimeout(props.getLlm().getRequestTimeout());
        return registry;
    }

    // ==================== 工具层 ====================

    @Bean("agentEngineSsrfValidator")
    @ConditionalOnMissingBean
    public SsrfValidator agentEngineSsrfValidator(AgentEngineProperties props) {
        return new SsrfValidator(props.getTool().getAllowedHosts());
    }

    @Bean
    @ConditionalOnMissingBean
    public CodeScanner codeScanner() {
        return new DefaultCodeScanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public SchemaValidator schemaValidator(ObjectMapper objectMapper) {
        return new SchemaValidator(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityChain securityChain(List<SecurityCheck> checks, SchemaValidator schemaValidator) {
        List<SecurityCheck> all = new ArrayList<>(checks);
        if (!all.contains(schemaValidator)) {
            all.add(schemaValidator);
        }
        return new SecurityChain(all);
    }

    @Bean
    @ConditionalOnMissingBean
    public CodeScannerCheck codeScannerCheck(CodeScanner codeScanner) {
        return new CodeScannerCheck(codeScanner);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(List<Tool> tools) {
        return new ToolRegistry(tools);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolConfigSource toolConfigSource() {
        return new NoOpToolConfigSource();
    }

    @Bean
    @ConditionalOnMissingBean
    public McpServerConfigSource mcpServerConfigSource() {
        return new NoOpMcpServerConfigSource();
    }

    @Bean("agentEngineMcpServerManager")
    @ConditionalOnMissingBean
    public McpServerManager agentEngineMcpServerManager(McpServerConfigSource configSource) {
        return new McpServerManager(configSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutor toolExecutor(ToolRegistry registry, SecurityChain securityChain,
                                     PermissionService permissionService, AuditService auditService,
                                     SandboxExecutor sandboxExecutor, McpServerManager mcpServerManager,
                                     ObjectMapper objectMapper, AgentEngineProperties props) {
        return new ToolExecutor(registry, securityChain, permissionService, auditService,
                sandboxExecutor, mcpServerManager, objectMapper,
                props.getTool().getMaxOutputSize(), props.getTool().getMaxRedirects(),
                props.getTool().getDefaultTimeoutSeconds());
    }

    // ==================== SPI 默认实现 ====================

    @Bean
    @ConditionalOnMissingBean
    public PersistenceService persistenceService() {
        return new NoOpPersistenceService();
    }

    @Bean("agentEnginePermissionService")
    @ConditionalOnMissingBean
    public PermissionService agentEnginePermissionService() {
        return new NoOpPermissionService();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditService auditService() {
        return new NoOpAuditService();
    }

    @Bean("agentEngineSessionContextService")
    @ConditionalOnMissingBean
    public SessionContextService agentEngineSessionContextService() {
        return new NoOpSessionContextService();
    }

    @Bean
    @ConditionalOnMissingBean
    public SandboxExecutor sandboxExecutor() {
        return new NoOpSandboxExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiKeyDecryptor apiKeyDecryptor() {
        return new NoOpApiKeyDecryptor();
    }

    // ==================== 消息构建 ====================

    @Bean
    @ConditionalOnMissingBean
    public SystemPromptComposer systemPromptComposer() {
        return new SystemPromptComposer();
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageBuilder messageBuilder(SystemPromptComposer composer) {
        return new DefaultMessageBuilder(composer);
    }

    // ==================== 核心引擎 ====================

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "agentToolExecutor")
    public ExecutorService agentToolExecutor(AgentEngineProperties props) {
        AgentEngineProperties.Engine e = props.getEngine();
        return new ThreadPoolExecutor(
                e.getToolExecutorCorePoolSize(),
                e.getToolExecutorMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(e.getToolExecutorQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "agent-tool-exec");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentEngineConfig agentEngineConfig(AgentEngineProperties props,
                                                ExecutorService agentToolExecutor) {
        AgentEngineProperties.Engine e = props.getEngine();
        return AgentEngineConfig.builder()
                .maxToolRounds(e.getMaxToolRounds())
                .defaultMaxTokens(e.getDefaultMaxTokens())
                .defaultTemperature(e.getDefaultTemperature())
                .toolExecutor(agentToolExecutor)
                .defaultHistoryLimit(e.getDefaultHistoryLimit())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentExecutor agentExecutor(LlmClientRegistry llmClientRegistry, ToolExecutor toolExecutor,
                                       MessageBuilder messageBuilder, SessionContextService sessionContextService,
                                       PersistenceService persistenceService, AgentEngineConfig config,
                                       ObjectMapper objectMapper, MemoryRouter memoryRouter,
                                       MemoryStore memoryStore, BudgetController budgetController,
                                       PerceptionEngine perceptionEngine, ComplexityRouter complexityRouter,
                                       TraceService traceService, MetricService metricService,
                                       ResponseCache responseCache,
                                       PromptInjectionDetector promptInjectionDetector,
                                       OutputSanitizer outputSanitizer,
                                       ModelSelector modelSelector) {
        return new ReactAgentExecutor(llmClientRegistry, toolExecutor, messageBuilder,
                sessionContextService, persistenceService, config, objectMapper, memoryRouter, memoryStore,
                budgetController, perceptionEngine, complexityRouter, traceService, metricService, responseCache,
                promptInjectionDetector, outputSanitizer, modelSelector);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentEngine agentEngine(AgentExecutor executor) {
        return new AgentEngine(executor);
    }

    // ==================== 记忆与认知 SPI ====================

    @Bean
    @ConditionalOnMissingBean
    public BudgetController budgetController(AgentEngineProperties props) {
        AgentEngineProperties.Engine e = props.getEngine();
        AgentEngineProperties.Budget b = props.getBudget();
        BudgetController.Quotas quotas = new BudgetController.Quotas();
        quotas.setL1TokenDivisor(b.getL1TokenDivisor());
        quotas.setL1TimeBudgetMs(b.getL1TimeBudgetMs());
        quotas.setL1MaxRounds(b.getL1MaxRounds());
        quotas.setL3TokenMultiplier(b.getL3TokenMultiplier());
        quotas.setL3TimeMultiplier(b.getL3TimeMultiplier());
        quotas.setL3RoundsMultiplier(b.getL3RoundsMultiplier());
        return new BudgetController(
                e.getDefaultMaxTokens() * 10L,
                b.getTimeBudgetMs(),
                e.getMaxToolRounds(),
                quotas);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfidenceGate confidenceGate() {
        return new ConfidenceGate();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryStore memoryStore() {
        return new NoOpMemoryStore();
    }

    @Bean("agentEngineMemoryRouter")
    @ConditionalOnMissingBean
    public MemoryRouter agentEngineMemoryRouter() {
        return new NoOpMemoryRouter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReasoningKernel reasoningKernel() {
        return new NoOpReasoningKernel();
    }

    @Bean
    @ConditionalOnMissingBean
    public EvolutionHook evolutionHook() {
        return new NoOpEvolutionHook();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReflectionEngine reflectionEngine() {
        return new NoOpReflectionEngine();
    }

    // ==================== 编排引擎 ====================

    @Bean
    @ConditionalOnMissingBean
    public RoleConfigSource roleConfigSource() {
        return new NoOpRoleConfigSource();
    }

    @Bean
    @ConditionalOnMissingBean
    public RoleRegistry roleRegistry(List<com.gewu.agent.engine.orchestration.role.AgentRoleSpec> specs,
                                     RoleConfigSource configSource) {
        var all = new ArrayList<com.gewu.agent.engine.orchestration.role.AgentRoleSpec>();
        var external = configSource.loadRoles();
        if (external != null) {
            all.addAll(external);
        }
        if (specs != null) {
            all.addAll(specs);
        }
        return new RoleRegistry(all);
    }

    @Bean
    @ConditionalOnMissingBean
    public HitlGateway hitlGateway() {
        return new NoOpHitlGateway();
    }

    @Bean
    @ConditionalOnMissingBean
    public Orchestrator orchestrator(AgentExecutor executor, HitlGateway hitlGateway,
                                      ObjectProvider<ConflictResolver> conflictResolverProvider,
                                      ObjectProvider<ArtifactValidator> artifactValidatorProvider) {
        return new Orchestrator(executor, hitlGateway,
                conflictResolverProvider.getIfAvailable(), artifactValidatorProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public GoalPlanner goalPlanner() {
        return new DefaultGoalPlanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public AutonomousExecutor autonomousExecutor(GoalPlanner goalPlanner, Orchestrator orchestrator,
                                                  EvolutionHook evolutionHook, ReasoningKernel reasoningKernel,
                                                  ConfidenceGate confidenceGate,
                                                  BudgetController budgetController,
                                                  AntiRunawayGuard antiRunawayGuard,
                                                  AgentLifecycleManager agentLifecycleManager,
                                                  ArbiterEngine arbiterEngine,
                                                  com.gewu.agent.engine.spi.TraceService traceService) {
        return new AutonomousExecutor(goalPlanner, orchestrator, evolutionHook, reasoningKernel,
                confidenceGate, new DualLoopVerifier(arbiterEngine), budgetController, antiRunawayGuard,
                agentLifecycleManager, traceService);
    }

    @Bean
    @ConditionalOnMissingBean
    public OrchestrationEngine orchestrationEngine(Orchestrator orchestrator, GoalPlanner goalPlanner,
                                                    AutonomousExecutor autonomousExecutor) {
        return new OrchestrationEngine(orchestrator, goalPlanner, autonomousExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConflictResolver conflictResolver(ReasoningKernel reasoningKernel, HitlGateway hitlGateway,
                                              ArbiterEngine arbiterEngine) {
        return new ConflictResolver(reasoningKernel, hitlGateway, arbiterEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public ArtifactValidator artifactValidator(ObjectMapper objectMapper) {
        return new ArtifactValidator(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DualSystemRouter dualSystemRouter() {
        return new DualSystemRouter();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentLifecycleManager agentLifecycleManager(AgentEngineProperties props) {
        return new AgentLifecycleManager(
                props.getLifecycle().getHeartbeatTimeoutMs(),
                props.getLifecycle().getGlobalTimeoutMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public ScenarioAdapterRegistry scenarioAdapterRegistry() {
        ScenarioAdapterRegistry registry = new ScenarioAdapterRegistry();
        registry.register(ScenarioAdapterRegistry.DEFAULT);
        registry.register(ScenarioAdapterRegistry.CODE_GENERATION);
        registry.register(ScenarioAdapterRegistry.KNOWLEDGE_QA);
        registry.register(ScenarioAdapterRegistry.ARCHITECTURE);
        return registry;
    }

    // ==================== 阶段六：认知/治理/安全 SPI ====================

    @Bean
    @ConditionalOnMissingBean
    public PerceptionEngine perceptionEngine() {
        return new NoOpPerceptionEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public ArbiterEngine arbiterEngine() {
        return new NoOpArbiterEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public ComplexityRouter complexityRouter(DualSystemRouter dualSystemRouter) {
        return new ComplexityRouter(dualSystemRouter);
    }

    @Bean
    @ConditionalOnMissingBean
    public AntiRunawayGuard antiRunawayGuard(BudgetController budgetController) {
        return new AntiRunawayGuard(budgetController);
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceService traceService() {
        return new TraceService() {
            @Override
            public void recordTrace(String executionId, String nodeId, String phase, String action, String detail) {
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponseCache responseCache() {
        return new ResponseCache() {};
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelSelector modelSelector() {
        return new NoOpModelSelector();
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricService metricService() {
        return new MetricService() {
            @Override
            public void recordMetric(String name, double value, java.util.Map<String, String> tags) {
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public PolicyService policyService() {
        return new PolicyService() {};
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptInjectionDetector promptInjectionDetector() {
        return new PromptInjectionDetector();
    }

    @Bean
    @ConditionalOnMissingBean
    public OutputSanitizer outputSanitizer() {
        return new OutputSanitizer();
    }
}
