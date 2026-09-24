package com.wannian.server.kernel.tool.builtin;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryPolicy;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.MemoryToolDraft;
import com.wannian.server.kernel.memory.SourceKind;
import com.wannian.server.kernel.memory.TurnMemoryPending;
import com.wannian.server.kernel.relationship.ApprovedRelationshipChange;
import com.wannian.server.kernel.relationship.RelationshipToolDraft;
import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * update_relationship：只产 Approved* 进 pending；禁止写库。pending 取自本次请求。
 */
public final class UpdateRelationshipToolAdapter implements ToolAdapter {

    private final MemoryPolicy policy;
    private final CompanionIdentity companion;

    public UpdateRelationshipToolAdapter(MemoryPolicy policy, CompanionIdentity companion) {
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
            String reason = ToolJson.requireString(fields, "reason");
            String preferredAddress =
                    ToolJson.optionalString(fields, "preferredAddress").orElse(null);
            String boundaries = ToolJson.optionalString(fields, "boundaries").orElse(null);

            RelationshipToolDraft draft =
                    new RelationshipToolDraft(companion, preferredAddress, boundaries, reason);

            String probeClaim =
                    String.join(
                            "\n",
                            draft.reason(),
                            draft.preferredAddress() == null ? "" : draft.preferredAddress(),
                            draft.boundaries() == null ? "" : draft.boundaries());
            MemoryToolDraft probe =
                    new MemoryToolDraft(
                            companion,
                            "relationship.probe",
                            probeClaim,
                            ContentKind.USER_FACT,
                            SourceKind.EXPLICIT,
                            MemoryScope.COMPANION,
                            0.5,
                            null);
            MemoryPolicy.PolicyResult decided = policy.evaluate(probe);
            if (decided instanceof MemoryPolicy.PolicyResult.Rejected rejected) {
                return new ToolAdapterResult.Failed(rejected.code(), rejected.message(), false);
            }

            pending.addRelationship(ApprovedRelationshipChange.fromToolDraft(draft));
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            out.put("status", "accepted");
            return new ToolAdapterResult.Succeeded(ToolJson.object(out));
        } catch (ToolJson.ToolJsonException | IllegalArgumentException ex) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_INVALID_ARGUMENTS, ex.getMessage(), false);
        }
    }
}
