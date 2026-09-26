import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import test from "node:test";

const source = await readFile(
  fileURLToPath(
    new URL("../../main/resources/META-INF/resources/chat/state.js", import.meta.url)
  ),
  "utf8"
);
const state = await import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);
const { applyHistoryPage, applyStreamEvent, createChatState, selectConversation } = state;

test("replayed run events do not recreate a completed historical assistant turn", () => {
  const state = createChatState();
  selectConversation(state, "conversation-a");
  applyHistoryPage(
    state,
    "conversation-a",
    [
      {
        id: "assistant-message",
        turnId: "turn-completed",
        role: "ASSISTANT",
        sequenceNo: 2,
        text: "完整的正式回复，远长于预览文本。",
        toolCalls: [{ name: "lookup", argumentsJson: "{\"callId\":\"call-1\"}", status: "SUCCEEDED" }],
      },
    ],
    { replace: true }
  );

  for (const [type, payload] of [
    ["turn.started", { turnId: "turn-completed", executionId: "exec-1", runSeq: 1 }],
    ["reply.delta", { turnId: "turn-completed", executionId: "exec-1", runSeq: 2, text: "旧增量" }],
    [
      "tool.started",
      {
        turnId: "turn-completed",
        executionId: "exec-1",
        runSeq: 3,
        callId: "call-replayed",
        name: "old-tool",
      },
    ],
    [
      "tool.updated",
      {
        turnId: "turn-completed",
        executionId: "exec-1",
        runSeq: 4,
        callId: "call-replayed",
        name: "old-tool",
        status: "SUCCEEDED",
      },
    ],
  ]) {
    applyStreamEvent(state, "conversation-a", type, payload);
  }

  const turn = state.committedByConversation["conversation-a"].items.find(
    (item) => item.kind === "assistant-turn" && item.turnId === "turn-completed"
  );
  assert.equal(turn.text, "完整的正式回复，远长于预览文本。");
  assert.equal(turn.temporary, false);
  assert.equal(turn.toolCalls.length, 1);
  assert.equal(state.inflightByTurnId["turn-completed"], undefined);
});

test("message.committed preview cannot replace a full historical message or its tools", () => {
  const state = createChatState();
  selectConversation(state, "conversation-a");
  applyHistoryPage(
    state,
    "conversation-a",
    [
      {
        id: "assistant-message",
        turnId: "turn-completed",
        role: "ASSISTANT",
        sequenceNo: 2,
        text: "完整正文超过 outbox preview。",
        toolCalls: [{ name: "lookup", argumentsJson: "{\"callId\":\"call-1\"}", status: "SUCCEEDED" }],
      },
    ],
    { replace: true }
  );

  applyStreamEvent(state, "conversation-a", "message.committed", {
    turnId: "turn-completed",
    messageId: "assistant-message",
    role: "ASSISTANT",
    textPreview: "preview",
  });

  const turn = state.committedByConversation["conversation-a"].items.find(
    (item) => item.kind === "assistant-turn" && item.turnId === "turn-completed"
  );
  assert.equal(turn.text, "完整正文超过 outbox preview。");
  assert.equal(turn.toolCalls.length, 1);
  assert.equal(turn.toolCalls[0].name, "lookup");
});
