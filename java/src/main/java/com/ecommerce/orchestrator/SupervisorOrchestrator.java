package com.ecommerce.orchestrator;

import com.ecommerce.agent.*;
import com.ecommerce.model.*;
import com.ecommerce.service.ABTestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Supervisor编排器 — 并行分发 + 聚合模式 (Java CompletableFuture实现)
 * 该类实现了推荐系统的核心编排逻辑，通过并行执行多个Agent来提高性能
 * 整体流程分为三个阶段：
 * 1. 并行执行用户画像和商品召回
 * 2. 并行执行商品重排和库存检查
 * 3. 生成营销文案
 *
 *        ┌──────────────┐
 *        │  Supervisor   │
 *        └──────┬───────┘
 *   ┌─────┬─────┼─────┬──────┐
 *   ▼     ▼     ▼     ▼      │
 * Profile Rec  Copy Inventory │
 *   └─────┴─────┴─────┘      │
 *          ▼                  │
 *      Aggregator ◄───────────┘
 */
@Service
public class SupervisorOrchestrator {

    // 日志记录器
    private static final Logger log = LoggerFactory.getLogger(SupervisorOrchestrator.class);



    // 依赖注入各个Agent服务
    private final UserProfileAgent userProfileAgent;    // 用户画像代理
    private final ProductRecAgent productRecAgent;       // 商品推荐代理
    private final MarketingCopyAgent marketingCopyAgent; // 营销文案代理
    private final InventoryAgent inventoryAgent;        // 库存代理
    private final ABTestService abTestService;          // A/B测试服务

    /**
     * 构造函数，注入所有依赖的Agent服务
     * @param userProfileAgent 用户画像代理
     * @param productRecAgent 商品推荐代理
     * @param marketingCopyAgent 营销文案代理
     * @param inventoryAgent 库存代理
     * @param abTestService A/B测试服务
     */
    public SupervisorOrchestrator(
            UserProfileAgent userProfileAgent,
            ProductRecAgent productRecAgent,
            MarketingCopyAgent marketingCopyAgent,
            InventoryAgent inventoryAgent,
            ABTestService abTestService) {
        this.userProfileAgent = userProfileAgent;
        this.productRecAgent = productRecAgent;
        this.marketingCopyAgent = marketingCopyAgent;
        this.inventoryAgent = inventoryAgent;
        this.abTestService = abTestService;
    }

    /**
     * 推荐系统主控制器方法，负责协调各个Agent完成推荐流程
     * 整个推荐流程分为三个阶段：
     * 1. 并行执行用户画像和商品召回
     * 2. 并行执行商品重排和库存检查
     * 3. 生成营销文案
     * @param request 推荐请求，包含用户ID和需要推荐的商品数量等信息
     * @return RecommendationResponse 推荐响应，包含推荐结果和相关元数据
     */
    public RecommendationResponse recommend(RecommendationRequest request) {
    // 生成唯一请求ID，用于追踪和日志记录
        String requestId = UUID.randomUUID().toString();
    // 记录开始时间，用于计算总延迟
        long start = System.nanoTime();
    // 存储各个Agent执行结果的Map
        Map<String, AgentResult> agentResults = new HashMap<>();

    // 记录开始处理请求的日志
        log.info("[Supervisor] start request={} user={}", requestId, request.getUserId());

    // A/B测试分组，确定用户属于哪个实验组
        String experimentGroup = abTestService.assign(request.getUserId()).getOrDefault("group", "control").toString();

        // Phase 1: parallel — user profile + product recall
        CompletableFuture<AgentResult> profileFuture = userProfileAgent.runAsync(
                Map.of("userId", request.getUserId()));
        CompletableFuture<AgentResult> recFuture = productRecAgent.runAsync(
                Map.of("numItems", request.getNumItems() * 2));

        AgentResult profileResult = profileFuture.join();
        AgentResult recResult = recFuture.join();
        agentResults.put("user_profile", profileResult);
        agentResults.put("product_recall", recResult);

        UserProfile profile = profileResult.getData() != null
                ? (UserProfile) profileResult.getData().get("profile") : null;
        @SuppressWarnings("unchecked")
        List<Product> rawProducts = recResult.getData() != null
                ? (List<Product>) recResult.getData().get("products") : List.of();

        // Phase 2: parallel — rerank + inventory check
        CompletableFuture<AgentResult> rerankFuture = productRecAgent.runAsync(
                Map.of("userProfile", profile != null ? profile : new UserProfile(),
                       "numItems", request.getNumItems()));
        CompletableFuture<AgentResult> inventoryFuture = inventoryAgent.runAsync(
                Map.of("products", rawProducts));

        AgentResult rerankResult = rerankFuture.join();
        AgentResult inventoryResult = inventoryFuture.join();
        agentResults.put("rerank", rerankResult);
        agentResults.put("inventory", inventoryResult);

        @SuppressWarnings("unchecked")
        List<Product> rankedProducts = rerankResult.getData() != null
                ? (List<Product>) rerankResult.getData().get("products") : rawProducts;
        @SuppressWarnings("unchecked")
        List<String> availableIds = inventoryResult.getData() != null
                ? (List<String>) inventoryResult.getData().get("available_products") : List.of();

        Set<String> availSet = new HashSet<>(availableIds);
        List<Product> finalProducts = rankedProducts.stream()
                .filter(p -> availSet.contains(p.getProductId()))
                .limit(request.getNumItems())
                .collect(Collectors.toList());
        if (finalProducts.isEmpty()) {
            finalProducts = rankedProducts.stream().limit(request.getNumItems()).collect(Collectors.toList());
        }

        // Phase 3: marketing copy
        AgentResult copyResult = marketingCopyAgent.runAsync(
                Map.of("userProfile", profile != null ? profile : new UserProfile(),
                       "products", finalProducts))
                .join();
        agentResults.put("marketing_copy", copyResult);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> copies = copyResult.getData() != null
                ? (List<Map<String, String>>) copyResult.getData().get("copies") : List.of();

        double totalLatency = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[Supervisor] complete request={} latency={:.1f}ms products={}", requestId, totalLatency, finalProducts.size());

        return RecommendationResponse.builder()
                .requestId(requestId)
                .userId(request.getUserId())
                .products(finalProducts)
                .marketingCopies(copies)
                .experimentGroup(experimentGroup)
                .agentResults(agentResults)
                .totalLatencyMs(totalLatency)
                .build();
    }
}
