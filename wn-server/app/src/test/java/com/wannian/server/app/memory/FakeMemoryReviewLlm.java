package com.wannian.server.app.memory;

import com.wannian.server.kernel.agent.ContextAssembler;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryReviewConstants;
import com.wannian.server.kernel.memory.MemoryReviewLlm;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.MemoryToolDraft;
import com.wannian.server.kernel.memory.SourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** 可注入的假 Review LLM，仅用于阶段验收和单测。 */
public final class FakeMemoryReviewLlm implements MemoryReviewLlm {

    private final List<MemoryToolDraft> nextDrafts = new CopyOnWriteArrayList<>();
    private final List<ReviewRequest> calls = new CopyOnWriteArrayList<>();

    /** 预设下一次 propose 返回的草案（可多次调用累积）。 */
    public void enqueueDraft(MemoryToolDraft draft) {
        nextDrafts.add(Objects.requireNonNull(draft, "draft"));
    }

    /** 便捷：一条 USER_FACT / OBSERVED / COMPANION。 */
    public void enqueueFact(
            CompanionIdentity companion, String subjectKey, String claim, double importance) {
        enqueueDraft(
                new MemoryToolDraft(
                        companion,
                        subjectKey,
                        claim,
                        ContentKind.USER_FACT,
                        SourceKind.OBSERVED,
                        MemoryScope.COMPANION,
                        importance,
                        null));
    }

    public List<ReviewRequest> calls() {
        return List.copyOf(calls);
    }

    public void clear() {
        nextDrafts.clear();
        calls.clear();
    }

    @Override
    public List<MemoryToolDraft> propose(ReviewRequest request) {
        Objects.requireNonNull(request, "request");
        calls.add(request);
        if (nextDrafts.isEmpty()) {
            return List.of();
        }
        List<MemoryToolDraft> out = new ArrayList<>(nextDrafts);
        nextDrafts.clear();
        return List.copyOf(out);
    }

    /** 单测工厂：带 Observation 锚校验友好的固定草案。 */
    public static MemoryToolDraft sampleDraft(String claim, double importance) {
        return new MemoryToolDraft(
                CompanionIdentity.YANHUO,
                "review.sample",
                claim,
                ContentKind.USER_FACT,
                SourceKind.OBSERVED,
                MemoryScope.COMPANION,
                importance,
                null);
    }

    /** 与 Assembler 一致的地点锚常量暴露给测试断言。 */
    public static String expectedPlaceAnchor() {
        return MemoryReviewConstants.PLACE_ANCHOR_UNSPECIFIED;
    }

    /** 近讯格式与 ContextAssembler 一致时的辅助。 */
    public static String labelUser(String text) {
        return "用户: " + text;
    }

    public static String excerptOf(String contentJson) {
        return ContextAssembler.textOf(contentJson);
    }
}
