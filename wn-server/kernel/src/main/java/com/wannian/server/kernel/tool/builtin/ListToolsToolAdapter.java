package com.wannian.server.kernel.tool.builtin;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolDescriptor;
import com.wannian.server.kernel.tool.ToolJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出本回合模型可见工具（名称、说明、参数 schema）。无参数。
 *
 * <p>只读当前 Turn 注入的可见集，不扫全机、不发明池外名字。
 */
public final class ListToolsToolAdapter implements ToolAdapter {

    @Override
    public ToolAdapterResult execute(ToolAdapterRequest request) {
        try {
            List<ToolDescriptor> visible = request.visibleTools();
            StringBuilder toolsJson = new StringBuilder("[");
            boolean first = true;
            for (ToolDescriptor d : visible) {
                if (!first) {
                    toolsJson.append(',');
                }
                first = false;
                LinkedHashMap<String, String> row = new LinkedHashMap<>();
                row.put("name", d.name());
                row.put("description", d.description());
                toolsJson.append(
                        ToolJson.objectWithRaw(
                                row, Map.of("parameters", d.parametersJsonSchema())));
            }
            toolsJson.append(']');
            LinkedHashMap<String, String> meta = new LinkedHashMap<>();
            meta.put("count", Integer.toString(visible.size()));
            return new ToolAdapterResult.Succeeded(
                    ToolJson.objectWithRaw(meta, Map.of("tools", toolsJson.toString())));
        } catch (Exception ex) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.INTERNAL_DEFECT, "list_tools 失败: " + ex.getMessage(), false);
        }
    }
}
