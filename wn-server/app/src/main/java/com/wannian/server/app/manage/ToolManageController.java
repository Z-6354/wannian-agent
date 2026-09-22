package com.wannian.server.app.manage;

import com.wannian.server.app.manage.ManageBodies.ErrorBody;
import com.wannian.server.app.manage.ManageBodies.ToolPoolEntryBody;
import com.wannian.server.app.manage.ManageBodies.ToolsBody;
import com.wannian.server.app.manage.ManageBodies.UpdateToolsRequest;
import com.wannian.server.app.manage.ManageBodies.YanhuoFacetsBody;
import com.wannian.server.kernel.tool.BuiltinToolPool;
import com.wannian.server.kernel.tool.FacetId;
import com.wannian.server.kernel.tool.HostCapabilitySet;
import com.wannian.server.kernel.tool.RoleId;
import com.wannian.server.kernel.tool.ToolDescriptor;
import com.wannian.server.kernel.tool.ToolUsePolicy;
import com.wannian.server.kernel.tool.ToolVisibility;
import com.wannian.server.kernel.tool.ToolVisibilityResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具启用与烟火模式可见集管理。系统 HostCapability 优先；返回三态与本机模型可见预览。
 */
@RestController
@RequestMapping("/api/manage/agent")
public class ToolManageController {

    private final ToolSettings toolSettings;
    private final ToolVisibilityResolver visibilityResolver;
    private final HostCapabilitySet hostCapabilities;

    public ToolManageController(
            ToolSettings toolSettings,
            ToolVisibilityResolver visibilityResolver,
            HostCapabilitySet hostCapabilitySet) {
        this.toolSettings = toolSettings;
        this.visibilityResolver = visibilityResolver;
        this.hostCapabilities = hostCapabilitySet;
    }

    @GetMapping("/tools")
    public ToolsBody getTools() {
        return toBody(toolSettings.snapshot());
    }

    @PutMapping("/tools")
    public ResponseEntity<?> putTools(@RequestBody(required = false) UpdateToolsRequest request) {
        if (request == null || request.enabled() == null || request.yanhuo() == null) {
            return error(ManageReason.ILLEGAL_ARGUMENT, "enabled / yanhuo 均必填");
        }
        YanhuoFacetsBody yanhuo = request.yanhuo();
        if (yanhuo.chat() == null || yanhuo.work() == null || yanhuo.research() == null) {
            return error(ManageReason.ILLEGAL_ARGUMENT, "yanhuo.chat / work / research 均必填");
        }
        try {
            ToolSettings.Snapshot snap =
                    toolSettings.update(
                            request.enabled(),
                            new ToolSettings.FacetLists(yanhuo.chat(), yanhuo.work(), yanhuo.research()));
            return ResponseEntity.ok(toBody(snap));
        } catch (IllegalArgumentException ex) {
            return error(ManageReason.ILLEGAL_ARGUMENT, ex.getMessage());
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorBody(ManageReason.DEPENDENCY_UNAVAILABLE, "写入 wannian.json 失败"));
        }
    }

    private ToolsBody toBody(ToolSettings.Snapshot snap) {
        Set<String> enabled = new LinkedHashSet<>(snap.enabled());
        List<ToolPoolEntryBody> pool = new ArrayList<>();
        for (BuiltinToolPool.Spec spec : BuiltinToolPool.allSpecs()) {
            BuiltinToolPool.PoolEntry entry = spec.toPoolEntry();
            String status = ToolUsePolicy.status(entry.name(), hostCapabilities, enabled);
            pool.add(
                    new ToolPoolEntryBody(
                            entry.name(),
                            entry.description(),
                            entry.requiredCapabilities(),
                            status,
                            ToolUsePolicy.selectable(entry.name(), hostCapabilities)));
        }
        return new ToolsBody(
                pool,
                snap.enabled(),
                new YanhuoFacetsBody(
                        snap.yanhuo().chat(), snap.yanhuo().work(), snap.yanhuo().research()),
                List.copyOf(hostCapabilities.asSet()),
                new YanhuoFacetsBody(
                        previewNames(FacetId.CHAT),
                        previewNames(FacetId.WORK),
                        previewNames(FacetId.RESEARCH)));
    }

    private List<String> previewNames(FacetId facet) {
        ToolVisibility visibility =
                visibilityResolver.resolve(RoleId.YANHUO, facet, hostCapabilities);
        List<String> names = new ArrayList<>();
        for (ToolDescriptor d : visibility.descriptors()) {
            names.add(d.name());
        }
        return List.copyOf(names);
    }

    private static ResponseEntity<ErrorBody> error(String code, String detail) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody(code, detail));
    }
}
