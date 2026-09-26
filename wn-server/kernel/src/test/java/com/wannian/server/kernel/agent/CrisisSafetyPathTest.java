package com.wannian.server.kernel.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.memory.InMemoryTurnMemoryPending;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelStreamObserver;
import com.wannian.server.kernel.model.ToolCallRequest;
import com.wannian.server.kernel.journal.JournalKind;
import com.wannian.server.kernel.journal.JournalSettings;
import com.wannian.server.kernel.journal.RunJournalEntry;
import com.wannian.server.kernel.prompt.CrisisResourceDirectory;
import com.wannian.server.kernel.prompt.CrisisRiskPolicy;
import com.wannian.server.kernel.tool.ToolDescriptor;
import com.wannian.server.kernel.tool.ToolExecutionOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CrisisSafetyPathTest {

    @Test
    void classifiesDistinctRiskLevelsWithAuditableReasons() {
        assertThat(CrisisRiskPolicy.classify("今天有点累").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("活着没意思").level())
                .isEqualTo(CrisisRiskPolicy.Level.WATCH);
        assertThat(CrisisRiskPolicy.classify("我想自杀").reasons())
                .contains("SELF_HARM_EXPLICIT");
        assertThat(CrisisRiskPolicy.classify("我不想自杀").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("我已经吞下大量药片").level())
                .isEqualTo(CrisisRiskPolicy.Level.IMMEDIATE);
        assertThat(CrisisRiskPolicy.classify("我已经吞下了很多药，救命").level())
                .isEqualTo(CrisisRiskPolicy.Level.IMMEDIATE);
        assertThat(CrisisRiskPolicy.classify("我服下了大量药物").level())
                .isEqualTo(CrisisRiskPolicy.Level.IMMEDIATE);
        assertThat(CrisisRiskPolicy.classify("我刚吞下了一把安眠药，救命").level())
                .isEqualTo(CrisisRiskPolicy.Level.IMMEDIATE);
        assertThat(CrisisRiskPolicy.classify("我没有吞下安眠药，只是查副作用").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("“我刚吞下了一把安眠药，救命”只是小说台词").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify(
                "小说里角色说‘我吞下了一把安眠药’，但这只是引用；我本人没有服药，也没有人处于危险。请分析这句台词。").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("我正在吃药，帮我查副作用").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("我正在服药，想知道副作用").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("我正在服用降压药").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("我现在就想自杀").level())
                .isEqualTo(CrisisRiskPolicy.Level.IMMEDIATE);
        assertThat(CrisisRiskPolicy.classify("我要伤害自己").level())
                .isEqualTo(CrisisRiskPolicy.Level.ELEVATED);
        assertThat(CrisisRiskPolicy.classify("朋友刚吞了很多药").level())
                .isEqualTo(CrisisRiskPolicy.Level.IMMEDIATE);
        assertThat(CrisisRiskPolicy.classify("朋友已经吞下了很多药，救命").level())
                .isEqualTo(CrisisRiskPolicy.Level.IMMEDIATE);
        assertThat(CrisisRiskPolicy.classify("她刚服下了大量药片").level())
                .isEqualTo(CrisisRiskPolicy.Level.IMMEDIATE);
        assertThat(CrisisRiskPolicy.classify("我想伤害别人，手里有武器，马上要去找他").level())
                .isEqualTo(CrisisRiskPolicy.Level.IMMEDIATE);
        assertThat(CrisisRiskPolicy.classify("我在写自杀预防报告").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("我没有自杀").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("我没有伤害自己").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("“我要自杀”只是电影台词").level())
                .isEqualTo(CrisisRiskPolicy.Level.NONE);
        assertThat(CrisisRiskPolicy.classify("我只是引用“我要自杀”，但我现在也想自杀").level())
                .isEqualTo(CrisisRiskPolicy.Level.ELEVATED);
    }

    @Test
    void crisisBypassesUnsafeDeltaThenFailureOrToolCallAndAuditsCodesOnly() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger observerRequests = new AtomicInteger();
        AtomicInteger streamedDeltas = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        List<RunJournalEntry> journal = new ArrayList<>();
        ModelPort model = new ModelPort() {
            @Override
            public ModelOutcome decide(com.wannian.server.kernel.model.ModelRequest request,
                    com.wannian.server.kernel.model.ModelCallContext context) {
                modelCalls.incrementAndGet();
                return new ModelOutcome.Failure("UPSTREAM", "unavailable", true);
            }

            @Override
            public ModelOutcome decide(com.wannian.server.kernel.model.ModelRequest request,
                    com.wannian.server.kernel.model.ModelCallContext context,
                    ModelStreamObserver observer) {
                modelCalls.incrementAndGet();
                observer.onTextDelta("unsafe crisis text");
                return new ModelOutcome.ToolCalls(List.of(new ToolCallRequest("c1", "dangerous", "{}")), null);
            }
        };
        DefaultAgentLoop loop = new DefaultAgentLoop(
                model, (invocation, context) -> {
                    toolCalls.incrementAndGet();
                    return new ToolExecutionOutcome.Succeeded(invocation.callId(), "{}");
                }, journal::add, JournalSettings.DEFAULT,
                () -> {
                    observerRequests.incrementAndGet();
                    return ignored -> streamedDeltas.incrementAndGet();
                }, AgentActivityListener.NOOP, CrisisResourceDirectory.empty());

        AgentOutcome outcome = loop.run(input("我现在就想自杀：private crisis text"),
                AgentBudget.of(2, 15, 30, Instant.now()));

        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        assertThat(modelCalls).hasValue(0);
        assertThat(observerRequests).hasValue(0);
        assertThat(streamedDeltas).hasValue(0);
        assertThat(toolCalls).hasValue(0);
        AgentOutcome.FinalResponse response = (AgentOutcome.FinalResponse) outcome;
        assertThat(response.text()).contains("所在地的紧急服务", "可信任的人")
                .doesNotContain("private crisis text", "988", "911", "999", "120");
        assertThat(String.join(" ", response.trace().steps()))
                .contains("CrisisRisk-IMMEDIATE", "CRISIS_SIGNALS_", "CRISIS_DETERMINISTIC_RESPONSE");
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0).kind()).isEqualTo(JournalKind.CRISIS_DECISION);
        assertThat(journal.get(0).requestJson())
                .contains("\"level\":\"IMMEDIATE\"", "SELF_HARM_EXPLICIT")
                .doesNotContain("private crisis text");
        assertThat(journal.get(0).resultJson()).contains("CRISIS_DETERMINISTIC_RESPONSE");
    }

    @Test
    void verifiedResourceIsUsedOnlyWhenCurrentAndExplicitlyResolved() {
        List<RunJournalEntry> journal = new ArrayList<>();
        CrisisResourceDirectory directory = message -> {
            assertThat(message).contains("我在XY");
            return java.util.Optional.of(new CrisisResourceDirectory.VerifiedResource(
                    "XY", "XY crisis support", "xy.example/help", Instant.now(), "regional-review"));
        };
        DefaultAgentLoop loop = new DefaultAgentLoop(
                (request, context) -> new ModelOutcome.FinalAnswer("unused", null),
                null, journal::add, JournalSettings.DEFAULT,
                () -> ignored -> {}, AgentActivityListener.NOOP, directory);

        AgentOutcome outcome = loop.run(input("我在XY，我想自杀"),
                AgentBudget.of(2, 15, 30, Instant.now()));

        assertThat(((AgentOutcome.FinalResponse) outcome).text())
                .contains("XY crisis support", "xy.example/help", "regional-review");
        assertThat(journal.get(0).requestJson())
                .contains("resourceRegion", "XY", "resourceVerifiedAt", "resourceVerifiedBy");
    }

    @Test
    void staleOrMissingRegionResourceFallsBackToGenericWording() {
        CrisisResourceDirectory staleDirectory = ignored -> java.util.Optional.of(
                new CrisisResourceDirectory.VerifiedResource(
                        "XY", "stale support", "stale.example", Instant.now().minusSeconds(400L * 24 * 60 * 60), "old-review"));
        DefaultAgentLoop loop = new DefaultAgentLoop(
                (request, context) -> { throw new IllegalStateException("must not call"); },
                null, journal -> {}, JournalSettings.DEFAULT,
                () -> ignored -> {}, AgentActivityListener.NOOP, staleDirectory);

        AgentOutcome outcome = loop.run(input("我在XY，我想自杀"),
                AgentBudget.of(2, 15, 30, Instant.now()));

        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        assertThat(((AgentOutcome.FinalResponse) outcome).text())
                .contains("所在地的紧急服务")
                .doesNotContain("stale support", "stale.example", "XY");
    }

    @Test
    void directoryCannotReturnResourceForARegionTheUserDidNotName() {
        CrisisResourceDirectory mismatchedDirectory = ignored -> java.util.Optional.of(
                new CrisisResourceDirectory.VerifiedResource(
                        "XY", "XY crisis support", "xy.example", Instant.now(), "regional-review"));
        DefaultAgentLoop loop = new DefaultAgentLoop(
                (request, context) -> { throw new IllegalStateException("must not call"); },
                null, journal -> {}, JournalSettings.DEFAULT,
                () -> ignored -> {}, AgentActivityListener.NOOP, mismatchedDirectory);

        AgentOutcome outcome = loop.run(input("我在ZZ，我想自杀"),
                AgentBudget.of(2, 15, 30, Instant.now()));

        assertThat(((AgentOutcome.FinalResponse) outcome).text())
                .contains("所在地的紧急服务")
                .doesNotContain("XY crisis support", "xy.example");
    }

    @Test
    void expiredBudgetAndEmptyModelAnswerCannotDropCrisisResponse() {
        AtomicInteger calls = new AtomicInteger();
        ModelPort model = (request, context) -> {
            calls.incrementAndGet();
            return new ModelOutcome.FinalAnswer("   ", null);
        };
        Instant past = Instant.now().minusSeconds(60);
        AgentBudget expired = new AgentBudget(1, past.minusSeconds(10), past,
                new AgentBudget.CancelToken());

        AgentOutcome outcome = new DefaultAgentLoop(model).run(input("我现在就想自杀"), expired);

        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        assertThat(((AgentOutcome.FinalResponse) outcome).text()).contains("所在地的紧急服务");
        assertThat(calls).hasValue(0);
    }

    @Test
    void ordinaryMessageStillCallsModelWithVoiceNudge() {
        AtomicInteger calls = new AtomicInteger();
        ModelPort model = (request, context) -> {
            calls.incrementAndGet();
            assertThat(request.messages()).anySatisfy(message ->
                    assertThat(message.content()).contains("本轮声音"));
            return new ModelOutcome.FinalAnswer("你好。", null);
        };

        AgentOutcome outcome = new DefaultAgentLoop(model).run(input("你好"),
                AgentBudget.of(2, 15, 30, Instant.now()));

        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void imminentWeaponThreatGetsDeterministicDistanceAndEmergencyResponse() {
        AtomicInteger calls = new AtomicInteger();
        ModelPort model = (request, context) -> {
            calls.incrementAndGet();
            return new ModelOutcome.FinalAnswer("unsafe", null);
        };

        AgentOutcome outcome = new DefaultAgentLoop(model).run(
                input("我想伤害别人，手里有武器，马上要去找他"),
                AgentBudget.of(2, 15, 30, Instant.now()));

        assertThat(CrisisRiskPolicy.classify("我想伤害别人，手里有武器，马上要去找他").reasons())
                .contains("HARM_TO_OTHERS_EXPLICIT", "WEAPON_ACCESS_EXPLICIT", "IMMEDIACY_LANGUAGE");
        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        String response = ((AgentOutcome.FinalResponse) outcome).text();
        assertThat(response).contains("保持距离", "靠近任何人", "不要去找", "放到地面", "后退",
                        "不要携带或再拿起", "急救服务或警方", "可信赖的人")
                .doesNotContain("把武器移到够不到处");
        assertThat(calls).hasValue(0);
    }

    @Test
    void medicationIngestionGetsImmediateMedicalResponse() {
        AgentOutcome outcome = new DefaultAgentLoop(
                (request, context) -> { throw new AssertionError("crisis must not call model"); })
                .run(input("我已经吞下了很多药，救命"),
                        AgentBudget.of(2, 15, 30, Instant.now()));

        assertThat(((AgentOutcome.FinalResponse) outcome).text())
                .contains("急救服务", "中毒咨询服务", "急诊", "不要等待症状", "不要自行催吐")
                .doesNotContain("120", "911", "988");
    }

    @Test
    void immediateNonMedicationSelfHarmGetsRelevantEmergencyResponse() {
        AgentOutcome outcome = new DefaultAgentLoop(
                (request, context) -> { throw new AssertionError("crisis must not call model"); })
                .run(input("我要跳楼"), AgentBudget.of(2, 15, 30, Instant.now()));

        assertThat(((AgentOutcome.FinalResponse) outcome).text())
                .contains("离开高处", "有人的安全地方", "急救服务", "可信赖的人")
                .doesNotContain("中毒咨询", "催吐");
    }

    @Test
    void nearbyWeaponGetsDistanceAdviceWithoutInstructionsToHandleIt() {
        AgentOutcome outcome = new DefaultAgentLoop(
                (request, context) -> new ModelOutcome.FinalAnswer("unused", null))
                .run(input("我想伤害别人，旁边有武器，马上要去找他"),
                        AgentBudget.of(2, 15, 30, Instant.now()));

        String response = ((AgentOutcome.FinalResponse) outcome).text();
        assertThat(response).contains("保持距离", "不要接触、拿起或搬动武器", "急救服务或警方")
                .doesNotContain("放到地面");
    }

    @Test
    void ordinaryToolCallStillExecutesAndReturnsFinalAnswer() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ModelPort model = (request, context) -> {
            if (modelCalls.incrementAndGet() == 1) {
                return new ModelOutcome.ToolCalls(
                        List.of(new ToolCallRequest("c1", "lookup", "{}")), null);
            }
            return new ModelOutcome.FinalAnswer("工具结果已处理", null);
        };
        DefaultAgentLoop loop = new DefaultAgentLoop(
                model,
                (invocation, context) -> {
                    toolCalls.incrementAndGet();
                    return new ToolExecutionOutcome.Succeeded(invocation.callId(), "{\"ok\":true}");
                });
        AgentInput input = new AgentInput(
                TurnId.generate(), null, TurnSource.USER, "", null, null, "帮我查一下",
                List.of(new ToolDescriptor("lookup", "查询", "{}", true)), null,
                "system", null, new InMemoryTurnMemoryPending());

        AgentOutcome outcome = loop.run(input, AgentBudget.of(3, 15, 30, Instant.now()));

        assertThat(outcome).isInstanceOf(AgentOutcome.FinalResponse.class);
        assertThat(modelCalls).hasValue(2);
        assertThat(toolCalls).hasValue(1);
    }

    private static AgentInput input(String message) {
        return new AgentInput(
                TurnId.generate(), null, TurnSource.USER, "", null, null, message,
                List.of(), null, "system", null, new InMemoryTurnMemoryPending());
    }
}
