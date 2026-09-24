package com.wannian.server.app.http;

import java.util.List;

/**
 * 记忆 HTTP JSON 形状（0.2.3-D S11-a）。
 *
 * <p>与 {@link MemoryHttpController} 配套；字段名对前端/curl 稳定。禁止在本类写业务逻辑。
 */
public final class MemoryHttpBodies {

    private MemoryHttpBodies() {}

    /** GET 列表中的单条 ACTIVE 投影。 */
    public record MemoryEntryBody(
            String id,
            String subjectKey,
            String claim,
            String contentKind,
            String sourceKind,
            String scope,
            double importance,
            String path,
            long revision,
            String createdAt) {}

    /** GET {@code /api/memory} 成功体。 */
    public record MemoryListBody(String companion, List<MemoryEntryBody> entries) {}

    /**
     * POST {@code /api/memory/correct} 请求。
     *
     * <p>必填：id、expectedRevision、subjectKey、claim、contentKind、sourceKind、scope、importance；
     * path 可选。Controller 再 Shape+Policy；proposeId 由服务端写死为 {@code http_correct}。
     */
    public record CorrectMemoryRequest(
            String id,
            Long expectedRevision,
            String subjectKey,
            String claim,
            String contentKind,
            String sourceKind,
            String scope,
            Double importance,
            String path) {}

    /** POST {@code /api/memory/forget} 请求。 */
    public record ForgetMemoryRequest(String id, Long expectedRevision) {}

    /** correct / forget 成功体（新行或遗忘后的 id + revision）。 */
    public record MemoryWriteBody(String id, long revision) {}

    /** 失败体；与会话 API 风格对齐（code + detail）。 */
    public record MemoryErrorBody(String code, String detail) {}
}
