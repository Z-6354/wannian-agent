package com.wannian.server.app.manage;

import com.wannian.server.app.manage.ManageBodies.AgentBudgetBody;
import com.wannian.server.app.manage.ManageBodies.ErrorBody;
import com.wannian.server.app.manage.ManageBodies.UpdateAgentBudgetRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 运行参数管理。读写数据目录 {@code wannian.json} 的 {@code agentBudget}，不改 Turn。
 */
@RestController
@RequestMapping("/api/manage/agent")
public class AgentManageController {

    private final AgentBudgetSettings budgetSettings;

    public AgentManageController(AgentBudgetSettings budgetSettings) {
        this.budgetSettings = budgetSettings;
    }

    @GetMapping("/budget")
    public AgentBudgetBody getBudget() {
        AgentBudgetSettings.Snapshot snap = budgetSettings.snapshot();
        return new AgentBudgetBody(
                snap.maxModelDecisions(), snap.softDeadlineSeconds(), snap.hardDeadlineSeconds());
    }

    @PutMapping("/budget")
    public ResponseEntity<?> putBudget(@RequestBody(required = false) UpdateAgentBudgetRequest request) {
        if (request == null
                || request.maxModelDecisions() == null
                || request.softDeadlineSeconds() == null
                || request.hardDeadlineSeconds() == null) {
            return error(ManageReason.ILLEGAL_ARGUMENT, "maxModelDecisions / softDeadlineSeconds / hardDeadlineSeconds 均必填");
        }
        try {
            AgentBudgetSettings.Snapshot snap =
                    budgetSettings.update(
                            request.maxModelDecisions(),
                            request.softDeadlineSeconds(),
                            request.hardDeadlineSeconds());
            return ResponseEntity.ok(
                    new AgentBudgetBody(
                            snap.maxModelDecisions(),
                            snap.softDeadlineSeconds(),
                            snap.hardDeadlineSeconds()));
        } catch (IllegalArgumentException ex) {
            return error(ManageReason.ILLEGAL_ARGUMENT, ex.getMessage());
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorBody(ManageReason.DEPENDENCY_UNAVAILABLE, "写入 wannian.json 失败"));
        }
    }

    private static ResponseEntity<ErrorBody> error(String code, String detail) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody(code, detail));
    }
}
