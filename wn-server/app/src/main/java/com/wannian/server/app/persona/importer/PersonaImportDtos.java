package com.wannian.server.app.persona.importer;

import java.util.List;
import java.time.Instant;
import com.wannian.server.kernel.persona.PersonaDefinition;

public final class PersonaImportDtos {
    private PersonaImportDtos() {}
    public enum Target { DEFAULT_YANHUO, NEW_PERSONA }
    public record Accepted(String importId, String status, Target target) {
        public Accepted(String importId,String status) { this(importId,status,Target.NEW_PERSONA); }
    }
    public record ImportStatus(String importId, String status, int progress, String candidatePersonaId,
            String errorCode, String errorSummary, Target target, Coverage coverage) {}
    public record Coverage(int scannedChapters, int matchedChapters, int modelChapters, int modelWindows,
            int inputChars, int unmodeledChapters, String algorithmVersion, List<WindowOffset> windows) {}
    public record WindowOffset(int startOffset, int endOffset, int chapter) {}
    public record SourceInfo(String sourceId, String fileName, String sha256, long byteCount,
            int codepointCount, boolean originalTextDeleted) {}
    public record Preview(PersonaDefinition persona, SourceInfo source, Coverage coverage, Target target, QualityReport quality) {
        public Preview(PersonaDefinition persona,SourceInfo source,Coverage coverage) {
            this(persona,source,coverage,source==null?Target.NEW_PERSONA:Target.NEW_PERSONA,null);
        }
    }
    public record QualityReport(boolean passed,List<String> reasons,int soulEvidenceChapters,int voiceEvidenceChapters,
            java.util.Map<String,Integer> overlayTraitEvidenceChapters,List<String> uncertainties) {
        public QualityReport(boolean passed,List<String> reasons,int soulEvidenceChapters,int voiceEvidenceChapters,List<String> uncertainties) {
            this(passed,reasons,soulEvidenceChapters,voiceEvidenceChapters,java.util.Map.of(),uncertainties);
        }
    }
    public record ApplyToDefaultRequest(long expectedOverlayRevision,String operationId) {}
    public record ApprovePromptMergeRequest(String operationId) {}
    public record RollbackDefaultRequest(long revision,long expectedCurrentRevision) {}
    public record DefaultOverlayState(boolean active,long revision,String sourcePersonaId,String soul,String voice,
            String evidenceSummary,Instant createdAt) {}
    /**
     * apply-to-default 只落临时合成稿；{@code personalityEffective=false} 表示性格尚未写入正式 prompts。
     * 仅 approve-prompt-merge 后才为 true。
     */
    public record PromptReviewStaging(String importId,String reviewDir,String soulFile,String voiceFile,
            String metaFile,String status,int soulObservationCount,int voiceObservationCount,
            boolean personalityEffective) {
        public PromptReviewStaging(String importId,String reviewDir,String soulFile,String voiceFile,
                String metaFile,String status) {
            this(importId,reviewDir,soulFile,voiceFile,metaFile,status,0,0,false);
        }
    }
    /** approve 后正式路径、overlay 是否清空、文件哈希与片段，便于核对性格已进对话。 */
    public record PromptMergeResult(String importId,String soulPath,String voicePath,
            long previousOverlayRevision,boolean overlayCleared,String backupDir,
            String soulSha256,String voiceSha256,String soulSnippet,String voiceSnippet,
            int soulObservationCount,int voiceObservationCount) {
        public PromptMergeResult(String importId,String soulPath,String voicePath,
                long previousOverlayRevision,boolean overlayCleared,String backupDir) {
            this(importId,soulPath,voicePath,previousOverlayRevision,overlayCleared,backupDir,
                    "","","","",0,0);
        }
    }
}
