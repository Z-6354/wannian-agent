package com.wannian.server.kernel.tool.builtin;

import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolJson;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 当前时间（可选 timezone，默认 Asia/Shanghai）。 */
public final class CurrentTimeToolAdapter implements ToolAdapter {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public ToolAdapterResult execute(ToolAdapterRequest request) {
        try {
            Map<String, String> fields = ToolJson.parseFlatObject(request.argumentsJson());
            ZoneId zone =
                    ToolJson.optionalString(fields, "timezone")
                            .map(ZoneId::of)
                            .orElse(DEFAULT_ZONE);
            ZonedDateTime now = ZonedDateTime.now(zone);
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            out.put("timezone", zone.getId());
            out.put("iso8601", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            out.put("epochMillis", Long.toString(now.toInstant().toEpochMilli()));
            return new ToolAdapterResult.Succeeded(ToolJson.object(out));
        } catch (Exception ex) {
            return new ToolAdapterResult.Failed(
                    com.wannian.server.kernel.error.ErrorCodes.INTERNAL_DEFECT,
                    "current_time 失败: " + ex.getMessage(),
                    false);
        }
    }
}
