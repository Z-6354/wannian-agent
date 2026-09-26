package com.wannian.server.kernel.persona;

import java.util.List;
import java.util.Objects;

/** 受限且可回溯的角色画像 schema v1。所有文本预算在构造时强制校验。 */
public record PersonaProfileV1(
        int schemaVersion, String displayName, String soul, String voice, String identity,
        List<SourceReference> sources, List<Evidence> evidence, DefaultPersonaOverlayTraits defaultOverlayTraits) {
    public static final int MAX_LAYER_CHARS = 1200;
    public static final int MAX_EVIDENCE = 32;
    public static final int MAX_EXCERPT_CHARS = 240;
    public PersonaProfileV1 {
        if (schemaVersion != 1) throw new IllegalArgumentException("仅支持 PersonaProfileV1");
        Objects.requireNonNull(displayName, "displayName");
        displayName = displayName.strip();
        if (displayName.isBlank() || displayName.length()>80) throw new IllegalArgumentException("displayName 为空或超出字符预算");
        soul = bounded(soul, "soul"); voice = bounded(voice, "voice"); identity = bounded(identity, "identity");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (sources.size() > 16 || evidence.size() > MAX_EVIDENCE) throw new IllegalArgumentException("画像来源或证据超限");
    }
    /** Compatibility constructor: older/import profiles have no enumerated default-overlay traits. */
    public PersonaProfileV1(int schemaVersion, String displayName, String soul, String voice, String identity,
                            List<SourceReference> sources, List<Evidence> evidence) {
        this(schemaVersion, displayName, soul, voice, identity, sources, evidence, null);
    }
    /** Compatibility constructor used by callers that have not yet supplied an explicit display name. */
    public PersonaProfileV1(int schemaVersion, String soul, String voice, String identity,
                            List<SourceReference> sources, List<Evidence> evidence) {
        this(schemaVersion, "新角色", soul, voice, identity, sources, evidence, null);
    }
    private static String bounded(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MAX_LAYER_CHARS) throw new IllegalArgumentException(name + " 为空或超出字符预算");
        return value.strip();
    }
    public record SourceReference(String sourceId, String label) {
        public SourceReference { Objects.requireNonNull(sourceId, "sourceId"); if (sourceId.length()>120) throw new IllegalArgumentException("sourceId 超限"); label=label==null?"":label.substring(0,Math.min(160,label.length())); }
    }
    public record Evidence(String sourceId, int startOffset, int endOffset, String excerpt, String inference, boolean uncertain) {
        public Evidence {
            Objects.requireNonNull(sourceId,"sourceId"); Objects.requireNonNull(excerpt,"excerpt"); Objects.requireNonNull(inference,"inference");
            if (startOffset < 0 || endOffset < startOffset || endOffset-startOffset > 100000) throw new IllegalArgumentException("证据偏移非法");
            if (excerpt.isBlank() || excerpt.length()>MAX_EXCERPT_CHARS || inference.isBlank() || inference.length()>400) throw new IllegalArgumentException("证据内容为空或超限");
        }
    }
}
