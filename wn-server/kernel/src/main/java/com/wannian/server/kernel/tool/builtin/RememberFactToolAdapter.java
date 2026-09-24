package com.wannian.server.kernel.tool.builtin;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryAxisNames;
import com.wannian.server.kernel.memory.MemoryPolicy;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.MemoryShape;
import com.wannian.server.kernel.memory.MemoryToolDraft;
import com.wannian.server.kernel.memory.SourceKind;
import com.wannian.server.kernel.memory.TurnMemoryPending;
import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * remember_fact：只产 Approved* 进 pending；禁止写库。pending 取自本次请求。
 */
public final class RememberFactToolAdapter implements ToolAdapter {

    private final MemoryShape shape;
    private final MemoryPolicy policy;
    private final CompanionIdentity companion;

    public RememberFactToolAdapter(
            MemoryShape shape, MemoryPolicy policy, CompanionIdentity companion) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.companion = Objects.requireNonNull(companion, "companion");
    }

    @Override
    public ToolAdapterResult execute(ToolAdapterRequest request) {
        TurnMemoryPending pending = request.pending();
        if (pending == null) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_UNAVAILABLE, "本回合未接线记忆 pending", false);
        }
        try {
            Map<String, String> fields = ToolJson.parseFlatObject(request.argumentsJson());
            String claim = ToolJson.requireString(fields, "claim");
            String subjectKey = ToolJson.requireString(fields, "subjectKey");
            String importanceRaw = ToolJson.requireString(fields, "importance");
            double importance = Double.parseDouble(importanceRaw);
            ContentKind contentKind =
                    MemoryAxisNames.parseContentKind(ToolJson.requireString(fields, "contentKind"));
            SourceKind sourceKind =
                    ToolJson.optionalString(fields, "sourceKind")
                            .map(MemoryAxisNames::parseSourceKind)
                            .orElse(SourceKind.EXPLICIT);
            MemoryScope scope =
                    ToolJson.optionalString(fields, "scope")
                            .map(MemoryAxisNames::parseScope)
                            .orElse(MemoryScope.COMPANION);
            String path = ToolJson.optionalString(fields, "path").orElse(null);

            MemoryToolDraft draft =
                    new MemoryToolDraft(
                            companion,
                            subjectKey,
                            claim,
                            contentKind,
                            sourceKind,
                            scope,
                            importance,
                            path);

            MemoryShape.ShapeResult shaped = shape.shape(draft);
            if (shaped instanceof MemoryShape.ShapeResult.Rejected rejected) {
                return new ToolAdapterResult.Failed(rejected.code(), rejected.message(), false);
            }
            MemoryToolDraft acceptedDraft =
                    ((MemoryShape.ShapeResult.Accepted) shaped).shaped();

            MemoryPolicy.PolicyResult decided = policy.evaluate(acceptedDraft);
            if (decided instanceof MemoryPolicy.PolicyResult.Rejected rejected) {
                return new ToolAdapterResult.Failed(rejected.code(), rejected.message(), false);
            }

            ApprovedMemoryChange change = ApprovedMemoryChange.fromToolDraft(acceptedDraft);
            long expectedGeneration = pending.expectedGeneration(companion, acceptedDraft.subjectKey());
            if (expectedGeneration >= 0) {
                change = change.withExpectedGeneration(expectedGeneration);
            }
            pending.addMemory(change);
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            out.put("status", "accepted");
            out.put("subjectKey", acceptedDraft.subjectKey());
            return new ToolAdapterResult.Succeeded(ToolJson.object(out));
        } catch (ToolJson.ToolJsonException | IllegalArgumentException ex) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_INVALID_ARGUMENTS, ex.getMessage(), false);
        }
    }
}
