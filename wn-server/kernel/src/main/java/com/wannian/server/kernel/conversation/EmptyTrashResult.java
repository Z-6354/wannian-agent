package com.wannian.server.kernel.conversation;

import java.util.Objects;

public sealed interface EmptyTrashResult {

    record Ok(int deletedCount, int skippedBusy) implements EmptyTrashResult {}

    record Rejected(String reasonCode, String detail) implements EmptyTrashResult {
        public Rejected {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
