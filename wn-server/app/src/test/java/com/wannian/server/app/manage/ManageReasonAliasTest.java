package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.kernel.error.ErrorCodes;
import org.junit.jupiter.api.Test;

/** ManageReason 须是 ErrorCodes 别名，不得另开第二套字符串。 */
class ManageReasonAliasTest {

    @Test
    void aliasesMatchRegistry() {
        assertThat(ManageReason.ILLEGAL_ARGUMENT).isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT);
        assertThat(ManageReason.REVISION_CONFLICT).isEqualTo(ErrorCodes.REVISION_CONFLICT);
        assertThat(ManageReason.DEPENDENCY_UNAVAILABLE)
                .isEqualTo(ErrorCodes.DEPENDENCY_UNAVAILABLE);
        assertThat(ManageReason.MODEL_NOT_ENABLED).isEqualTo(ErrorCodes.MODEL_NOT_ENABLED);
        assertThat(ManageReason.MANAGE_UNCONFIGURED).isEqualTo(ErrorCodes.MANAGE_UNCONFIGURED);
        assertThat(ErrorCodes.isRegistered(ManageReason.VENDOR_NOT_FOUND)).isTrue();
    }
}
