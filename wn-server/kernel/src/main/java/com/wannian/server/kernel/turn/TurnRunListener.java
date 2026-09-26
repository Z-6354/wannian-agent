package com.wannian.server.kernel.turn;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;

/** Turn 进入 RUNNING 后的可选钩子（运行中 turn.started）。 */
@FunctionalInterface
public interface TurnRunListener {

    TurnRunListener NOOP = (conversationId, turnId, executionId) -> {};

    void onRunning(ConversationId conversationId, TurnId turnId, String executionId);

    /** Called only after the successful turn commit is durable. */
    default void onCompleted(ConversationId conversationId, TurnId turnId, String executionId) {}

    /** Called after a terminal failure/cancellation; implementations ignore non-terminal turns. */
    default void onAborted(ConversationId conversationId, TurnId turnId, String executionId) {}
}
