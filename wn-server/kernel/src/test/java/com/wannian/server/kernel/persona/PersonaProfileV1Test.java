package com.wannian.server.kernel.persona;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PersonaProfileV1Test {
    @Test void acceptsBoundedProfileAndStableEvidenceShape() {
        var profile = new PersonaProfileV1(1,"谨慎","短句","杜小洛",
                List.of(new PersonaProfileV1.SourceReference("source-1","小说")),
                List.of(new PersonaProfileV1.Evidence("source-1",4,8,"原文摘录","据此推断语气克制",true)));
        assertEquals(1, profile.schemaVersion());
        assertEquals(4, profile.evidence().getFirst().startOffset());
    }

    @Test void rejectsUnsupportedVersionAndOversizedLayer() {
        assertThrows(IllegalArgumentException.class, () -> new PersonaProfileV1(2,"s","v","i",List.of(),List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PersonaProfileV1(1,"x".repeat(1201),"v","i",List.of(),List.of()));
    }
}
