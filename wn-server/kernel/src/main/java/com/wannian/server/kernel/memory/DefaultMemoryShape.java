package com.wannian.server.kernel.memory;

import java.util.Objects;

/**
 * 默认 MemoryShape（用户选 A）：仅钳制 {@code importance} 到 [0,1]。
 *
 * <p>claim 规范化归宿主锚 + 模型；本类不做夹具词拒写、不改写正文。
 */
public final class DefaultMemoryShape implements MemoryShape {

    @Override
    public ShapeResult shape(MemoryToolDraft draft) {
        Objects.requireNonNull(draft, "draft");
        double clamped = clamp01(draft.importance());
        if (clamped == draft.importance()) {
            return new ShapeResult.Accepted(draft);
        }
        return new ShapeResult.Accepted(
                new MemoryToolDraft(
                        draft.companionIdentity(),
                        draft.subjectKey(),
                        draft.claim(),
                        draft.contentKind(),
                        draft.sourceKind(),
                        draft.scope(),
                        clamped,
                        draft.path()));
    }

    private static double clamp01(double importance) {
        if (importance < 0.0) {
            return 0.0;
        }
        if (importance > 1.0) {
            return 1.0;
        }
        return importance;
    }
}
