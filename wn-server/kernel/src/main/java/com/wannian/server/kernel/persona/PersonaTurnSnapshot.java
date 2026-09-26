package com.wannian.server.kernel.persona;

import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.tool.RoleId;
import java.util.Objects;

/** 一个 turn 首次认领时冻结的角色及权限/身份映射。 */
public record PersonaTurnSnapshot(PersonaId personaId, int definitionRevision, long bindingRevision,
                                  String soul, String voice, String identity,
                                  CompanionIdentity companionIdentity, RoleId toolRoleId,
                                  long defaultOverlayRevision, String defaultOverlaySoul, String defaultOverlayVoice) {
    public PersonaTurnSnapshot {
        Objects.requireNonNull(personaId); Objects.requireNonNull(soul); Objects.requireNonNull(voice); Objects.requireNonNull(identity);
        Objects.requireNonNull(companionIdentity); Objects.requireNonNull(toolRoleId);
        defaultOverlaySoul = defaultOverlaySoul == null ? "" : defaultOverlaySoul;
        defaultOverlayVoice = defaultOverlayVoice == null ? "" : defaultOverlayVoice;
        if (definitionRevision < 0 || bindingRevision < 0) throw new IllegalArgumentException("revision 不可为负");
        if (defaultOverlayRevision < 0) throw new IllegalArgumentException("overlay revision 不可为负");
        if (defaultOverlaySoul.length() + defaultOverlayVoice.length() > 1600) throw new IllegalArgumentException("默认角色 overlay 超出字符预算");
    }

    public PersonaTurnSnapshot(PersonaId personaId, int definitionRevision, long bindingRevision,
                               String soul, String voice, String identity,
                               CompanionIdentity companionIdentity, RoleId toolRoleId) {
        this(personaId, definitionRevision, bindingRevision, soul, voice, identity, companionIdentity,
                toolRoleId, 0, "", "");
    }
}
