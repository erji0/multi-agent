package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 推荐接口响应体，由 SupervisorOrchestrator 聚合各 Agent 执行结果后构建返回。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationResponse {

    /** 请求唯一标识，用于链路追踪和日志关联 */
    private String requestId;

    /** 被推荐的用户 ID，与请求中的 userId 对应 */
    private String userId;

    /** 最终推荐商品列表，已完成召回 -> LLM 重排 -> 库存过滤 */
    private List<Product> products;

    /** 与推荐商品一一对应的个性化营销文案，格式为 [{product_id, copy}] */
    private List<Map<String, String>> marketingCopies;

    /** 用户所属的 A/B 实验分组（control / treatment_llm / llm_rerank 等） */
    private String experimentGroup;

    /** 各 Agent 的原始执行结果，key 为 agentName（user_profile / product_recall / inventory / marketing_copy 等） */
    private Map<String, AgentResult> agentResults;

    /** 整个推荐链路的总耗时，单位毫秒 */
    private double totalLatencyMs;

    /** 响应生成时间戳，默认使用构造时的当前时间 */
    @Builder.Default
    private Instant timestamp = Instant.now();
}