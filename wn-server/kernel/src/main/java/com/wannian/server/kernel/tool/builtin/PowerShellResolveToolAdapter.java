package com.wannian.server.kernel.tool.builtin;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.tool.PowerShellFamilyProbe;
import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolJson;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Windows PowerShell 解析报告：构造时固定 family（5 或 7），execute 不再在 5/7 间选型。
 */
public final class PowerShellResolveToolAdapter implements ToolAdapter {

    private final int familyMajor;

    /** @param familyMajor 仅允许 5 或 7 */
    public PowerShellResolveToolAdapter(int familyMajor) {
        if (familyMajor != 5 && familyMajor != 7) {
            throw new IllegalArgumentException("familyMajor 仅允许 5 或 7");
        }
        this.familyMajor = familyMajor;
    }

    @Override
    public ToolAdapterResult execute(ToolAdapterRequest request) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_UNAVAILABLE,
                    "powershell_resolve_" + familyMajor + " 仅在 Windows 可用",
                    false);
        }
        var engine = PowerShellFamilyProbe.findEngineForFamily(familyMajor);
        if (engine.isEmpty()) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_UNAVAILABLE,
                    "未找到 PowerShell family" + familyMajor + " 引擎",
                    false);
        }
        PowerShellFamilyProbe.ProbedEngine e = engine.get();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put("engine", e.engineLabel());
        out.put("executable", e.executable());
        out.put("version", e.version() == null ? "" : e.version());
        out.put("family", Integer.toString(familyMajor));
        return new ToolAdapterResult.Succeeded(ToolJson.object(out));
    }
}
