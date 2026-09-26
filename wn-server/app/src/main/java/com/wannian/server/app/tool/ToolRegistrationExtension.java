package com.wannian.server.app.tool;

import com.wannian.server.kernel.tool.ToolRegistration;
import java.util.Collection;
import java.util.Set;

/** Core extension seam for narrowly scoped app-owned tools. Never derives permissions from persona data. */
public interface ToolRegistrationExtension {
    Collection<ToolRegistration> registrations();
    /** Only these read-only names are appended to the default yanhuo facet bindings. */
    Set<String> defaultVisibleReadOnlyNames();
    /** Write operations are registered and bound, then guarded against the untrusted TXT/task contents. */
    default Set<String> explicitIntentWriteNames() { return Set.of(); }
    /** Subset of explicit-intent writes that may be offered to the model by default; still user-intent gated. */
    default Set<String> defaultEnabledExplicitIntentWriteNames() { return Set.of(); }
}
