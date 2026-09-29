package com.wannian.server.kernel.tool.builtin;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.tool.BuiltinToolNames;
import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolJson;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * propose_background_task：只校验参数并回传提案回声；<strong>不写库</strong>。
 *
 * <p>Loop 在 Succeeded 后组装 {@code TaskProposal}、跑 Policy，再决定 BackgroundAccepted。
 */
public final class ProposeBackgroundTaskToolAdapter implements ToolAdapter {

    @Override
    public ToolAdapterResult execute(ToolAdapterRequest request) {
        try {
            Map<String, String> fields = ToolJson.parseFlatObject(request.argumentsJson());
            String taskType = ToolJson.requireString(fields, "taskType");
            String inputJson = ToolJson.requireString(fields, "inputJson");
            String acknowledgementText = ToolJson.requireString(fields, "acknowledgementText");
            if (acknowledgementText.isBlank()) {
                return new ToolAdapterResult.Failed(
                        ErrorCodes.TOOL_INVALID_ARGUMENTS, "acknowledgementText 不能为空", false);
            }
            String notifyPolicy = ToolJson.optionalString(fields, "notifyPolicy").orElse("USER_VISIBLE");
            String delay = ToolJson.optionalString(fields, "delay").orElse(null);

            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            out.put("status", "PROPOSED");
            out.put("tool", BuiltinToolNames.PROPOSE_BACKGROUND_TASK);
            out.put("taskType", taskType);
            out.put("inputJson", inputJson);
            out.put("acknowledgementText", acknowledgementText);
            out.put("notifyPolicy", notifyPolicy);
            if (delay != null) {
                out.put("delay", delay);
            }
            out.put("note", "提案已形成，等待策略裁决与用户确认；本工具未写库");
            return new ToolAdapterResult.Succeeded(ToolJson.object(out));
        } catch (ToolJson.ToolJsonException | IllegalArgumentException ex) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_INVALID_ARGUMENTS,
                    ex.getMessage() == null ? "参数非法" : ex.getMessage(),
                    false);
        }
    }
}
