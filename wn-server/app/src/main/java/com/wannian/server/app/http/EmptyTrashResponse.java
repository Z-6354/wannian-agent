package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmptyTrashResponse(
        String result, Integer deletedCount, Integer skippedBusy, String reasonCode, String detail) {

    static EmptyTrashResponse rejected(String code, String detail) {
        return new EmptyTrashResponse("rejected", null, null, code, detail);
    }
}
