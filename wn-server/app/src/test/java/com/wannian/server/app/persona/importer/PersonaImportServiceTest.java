package com.wannian.server.app.persona.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.app.model.EnabledModelPortResolver.ResolveResult;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.model.ModelUsage;
import com.wannian.server.kernel.persona.PersonaCatalog;
import com.wannian.server.kernel.persona.PersonaDefinition;
import com.wannian.server.kernel.persona.PersonaId;
import com.wannian.server.kernel.persona.PersonaProfileV1;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;

class PersonaImportServiceTest {
    @TempDir Path temp;

    @Test
    void modelJsonParserAcceptsOneObjectInMarkdownFenceOrShortPreamble() {
        String json="{\"schemaVersion\":1}";
        assertThat(PersonaImportService.extractJson("```json\n"+json+"\n```" )).isEqualTo(json);
        assertThat(PersonaImportService.extractJson("结果如下：\n"+json+"\n已完成。" )).isEqualTo(json);
    }

    @Test
    void modelJsonParserRejectsMultipleObjectsAndTruncatedOutput() {
        // 若首个对象完整可解析，容忍其后的多余对象/说明（实模型常见）。
        assertThat(PersonaImportService.extractJson("{\"a\":1} {\"b\":2}")).isEqualTo("{\"a\":1}");
        assertThat(PersonaImportService.extractJson("{\"a\":1} true")).isEqualTo("{\"a\":1}");
        assertThat(PersonaImportService.extractJson("{\"a\":1} []")).isEqualTo("{\"a\":1}");
        assertThatThrownBy(()->PersonaImportService.extractJson("{\"a\":1"))
                .hasMessageContaining("未闭合");
        assertThatThrownBy(()->PersonaImportService.extractJson("[{\"a\":1}]"))
                .hasMessageContaining("前置文本");
    }

    @Test
    void modelJsonParserAcceptsUnescapedNewlinesInsideStrings() {
        String json="{\"schemaVersion\":1,\"displayName\":\"杜小洛\",\"soul\":\"第一行\n第二行\",\"voice\":\"简\",\"identity\":\"杜小洛\","
                +"\"sources\":[{\"sourceId\":\"s1\",\"label\":\"t\"}],"
                +"\"evidence\":[{\"sourceId\":\"s1\",\"startOffset\":0,\"endOffset\":3,\"excerpt\":\"杜小洛\",\"inference\":\"[SOUL] x\",\"uncertain\":false}]}";
        assertThat(PersonaImportService.extractJson(json)).isEqualTo(json);
    }

    @Test
    void normalizeProfileNodeMapsAliasesAndCoercesOverlayTraits() throws Exception {
        var mapper=new ObjectMapper();
        var root=mapper.readTree("""
                {"schemaVersion":1,"displayName":"杜小洛","soul":"温和","voice":"简洁","identity":"杜小洛",
                 "sources":[{"sourceId":"s1","description":"书"}],
                 "evidence":[{"sourceId":"s1","start":4,"end":7,"quote":"杜小洛","inference":"[SOUL] 温和"}],
                 "defaultOverlayTraits":{"clothingStyle":"Lolita","interactionStyle":"gentle","temperament":"俏皮"}}
                """);
        var normalized=PersonaImportService.normalizeProfileNode((com.fasterxml.jackson.databind.node.ObjectNode) root);
        assertThat(normalized.path("sources").get(0).path("label").asText()).isEqualTo("书");
        assertThat(normalized.path("evidence").get(0).path("excerpt").asText()).isEqualTo("杜小洛");
        assertThat(normalized.path("evidence").get(0).path("startOffset").asInt()).isEqualTo(4);
        assertThat(normalized.path("evidence").get(0).path("endOffset").asInt()).isEqualTo(7);
        assertThat(normalized.path("evidence").get(0).path("uncertain").asBoolean()).isFalse();
        assertThat(normalized.path("defaultOverlayTraits").path("interactionStyle").asText()).isEqualTo("GENTLE");
        assertThat(normalized.path("defaultOverlayTraits").path("responsePace").asText()).isEqualTo("UNKNOWN");
        assertThat(normalized.path("defaultOverlayTraits").has("clothingStyle")).isFalse();
    }

    @Test
    void normalizeProfileNodeCoercesSchemaVersionString() throws Exception {
        var mapper=new ObjectMapper();
        var root=mapper.readTree("""
                {"schemaVersion":"1.0","displayName":"杜小洛","soul":"温和","voice":"简洁",
                 "sources":[{"sourceId":"s1","label":"书"}],
                 "evidence":[{"sourceId":"s1","startOffset":"0","endOffset":"3","excerpt":"杜小洛","inference":"[SOUL] 温和"}]}
                """);
        var normalized=PersonaImportService.normalizeProfileNode((com.fasterxml.jackson.databind.node.ObjectNode) root);
        assertThat(normalized.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(normalized.path("identity").asText()).isEqualTo("杜小洛");
        assertThat(normalized.path("evidence").get(0).path("startOffset").asInt()).isEqualTo(0);
    }

    @Test
    void selectDiverseEvidencePrefersCertainAcrossChapters() {
        var w1=new PersonaTextScanner.Window(1,0,100);
        var w2=new PersonaTextScanner.Window(2,100,200);
        var w3=new PersonaTextScanner.Window(3,200,300);
        var certain1=new PersonaProfileV1.Evidence("s1",10,13,"杜小洛","[SOUL] a",false);
        var certain2=new PersonaProfileV1.Evidence("s1",110,113,"杜小洛","[SOUL] b",false);
        var uncertain=new PersonaProfileV1.Evidence("s1",210,213,"杜小洛","[SOUL] c",true);
        var dup=new PersonaProfileV1.Evidence("s1",10,13,"杜小洛","[SOUL] a",false);
        var selected=PersonaImportService.selectDiverseEvidence(List.of(uncertain,certain2,certain1,dup),List.of(w1,w2,w3),32);
        assertThat(selected).containsExactly(certain1,certain2,uncertain);
    }

    @Test
    void retagEvidenceForMergedTraitsAlignsMarkersWithFinalEnums() {
        var traits=new com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits(
                com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits.InteractionStyle.GENTLE,
                com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits.ResponsePace.BALANCED,
                com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits.Initiative.LOW,
                com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits.Humor.LIGHT);
        var e1=new PersonaProfileV1.Evidence("s1",0,3,"杜小洛","温和亲近 [STYLE:PLAYFUL]",false);
        var e2=new PersonaProfileV1.Evidence("s1",10,13,"杜小洛","语气软 [STYLE:CALM]",false);
        var retagged=PersonaImportService.retagEvidenceForMergedTraits(List.of(e1,e2),traits);
        assertThat(retagged.get(0).inference()).startsWith("[SOUL]");
        assertThat(retagged.get(1).inference()).startsWith("[VOICE]");
        assertThat(retagged.get(0).inference()).contains("[STYLE:GENTLE]").contains("[PACE:BALANCED]");
        assertThat(retagged.get(0).inference()).doesNotContain("[STYLE:PLAYFUL]");
        assertThat(retagged.get(1).inference()).contains("[STYLE:GENTLE]");
    }

    @Test
    void normalizeProfileNodeFillsEmptySoulVoiceAndMarkers() throws Exception {
        var mapper=new ObjectMapper();
        var root=mapper.readTree("""
                {"schemaVersion":1,"displayName":"杜小洛","soul":"","voice":"",
                 "sources":[{"sourceId":"s1","label":"书"}],
                 "evidence":[{"sourceId":"s1","startOffset":0,"endOffset":3,"excerpt":"杜小洛","inference":"温和"}],
                 "defaultOverlayTraits":{"interactionStyle":"GENTLE","responsePace":"BALANCED","initiative":"LOW","humor":"LIGHT"}}
                """);
        var normalized=PersonaImportService.normalizeProfileNode((com.fasterxml.jackson.databind.node.ObjectNode) root);
        assertThat(normalized.path("soul").asText()).isNotBlank();
        assertThat(normalized.path("voice").asText()).isNotBlank();
        assertThat(normalized.path("evidence").get(0).path("inference").asText()).contains("[SOUL]").contains("[STYLE:GENTLE]");
    }

    @Test
    void repairEvidenceOffsetsRelocatesMismatchedExcerptInsideModelledWindow() {
        int[] cps="前文杜小洛后文".codePoints().toArray();
        var window=new PersonaTextScanner.Window(1,0,cps.length);
        // offsets inside window but slice is 前文, not 杜小洛
        var bad=new PersonaProfileV1.Evidence("s1",0,3,"杜小洛","[SOUL] 温和",false);
        var repaired=PersonaImportService.repairEvidenceOffsets(List.of(bad),"s1",cps,List.of(window));
        assertThat(repaired).hasSize(1);
        assertThat(repaired.getFirst().startOffset()).isEqualTo(2);
        assertThat(repaired.getFirst().endOffset()).isEqualTo(5);
        assertThat(new String(cps,repaired.getFirst().startOffset(),
                repaired.getFirst().endOffset()-repaired.getFirst().startOffset())).isEqualTo("杜小洛");
    }

    @Test
    void repairEvidenceOffsetsDoesNotRescueOutsideWindowFabrication() {
        int[] cps="前文杜小洛后文".codePoints().toArray();
        var window=new PersonaTextScanner.Window(1,0,cps.length);
        var forged=new PersonaProfileV1.Evidence("s1",9000,9003,"杜小洛","伪造",false);
        var repaired=PersonaImportService.repairEvidenceOffsets(List.of(forged),"s1",cps,List.of(window));
        assertThat(repaired.getFirst().startOffset()).isEqualTo(9000);
    }

    @Test
    void fakeModelSuccessCreatesDraftOnlyAfterTwoVerifiedEvidence() throws Exception {
        Fixture f = fixture(false);
        var accepted=f.service.upload(file(),"杜小洛","success-request");
        var status=f.service.get(accepted.importId());
        assertThat(status.status()).as("%s: %s",status.errorCode(),status.errorSummary()).isEqualTo("SUCCEEDED");
        assertThat(status.candidatePersonaId()).isEqualTo("persona_test");
        assertThat(f.catalog.profile.evidence()).hasSize(2);
        assertThat(f.catalog.profile.evidence().getFirst().excerpt()).startsWith("杜小洛笑了");
        assertThat(status.coverage().scannedChapters()).isEqualTo(1);
        assertThat(status.coverage().modelWindows()).isEqualTo(1);
    }

    @Test
    void fakeModelFailureLeavesImportFailedAndDoesNotCreatePersona() throws Exception {
        Fixture f = fixture(true);
        var accepted=f.service.upload(file(),"杜小洛","failure-request");
        var status=f.service.get(accepted.importId());
        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.errorCode()).isEqualTo("MODEL_FAILED");
        assertThat(f.catalog.created).isFalse();
    }

    @Test
    void fabricatedEvidenceOutsideModeledWindowsCannotCreateDraft() throws Exception {
        String book="杜小洛笑了。"+"后续".repeat(5_000)+"杜小洛说话。";
        Fixture f=fixture(false,true);
        var accepted=f.service.upload(new MockMultipartFile("file","long.txt","text/plain",book.getBytes(StandardCharsets.UTF_8)),"杜小洛","forged-evidence");
        var status=f.service.get(accepted.importId());
        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.errorCode()).isEqualTo("INVALID_EVIDENCE");
        assertThat(f.catalog.created).isFalse();
    }

    @Test
    void quotedUnmatchedChapterIsScannedButNeverSentToModel() {
        var scan=new PersonaTextScanner().scan("1、引子\n“她说了一句话。”\n2、相遇\n杜小洛把伞递给我。", "杜小洛");
        assertThat(scan.chapters()).hasSize(2);
        assertThat(scan.matched()).isEqualTo(1);
        assertThat(scan.windows()).hasSize(1);
        assertThat(scan.windows().getFirst().chapter()).isEqualTo(2);
    }

    @Test
    void modelSummariesFromEarlyMiddleAndLateBatchesSurviveTheFinalProfile() throws Exception {
        StringBuilder book=new StringBuilder();
        for(int chapter=1;chapter<=12;chapter++) {
            book.append(chapter).append("、章节\n").append("铺垫".repeat(1700));
            book.append("杜小洛出现，她说：内容。\n").append("后续".repeat(1700)).append('\n');
        }
        Fixture f=fixture(false);
        var accepted=f.service.upload(new MockMultipartFile("file","chapters.txt","text/plain",book.toString().getBytes(StandardCharsets.UTF_8)),"杜小洛","multi-batch");
        var status=f.service.get(accepted.importId());
        assertThat(status.status()).isEqualTo("SUCCEEDED");
        // base 提示变长、单批约 1 窗后，批次数会明显增加；关键是跨前/中/后段摘要都保留。
        assertThat(f.calls.get()).isBetween(3, 48);
        assertThat(status.coverage().modelChapters()).isEqualTo(12);
        assertThat(status.coverage().modelWindows()).isEqualTo(12);
        assertThat(status.coverage().inputChars()).isLessThanOrEqualTo(320_000);
        assertThat(f.outputChars.get()).isLessThanOrEqualTo(24_000);
        assertThat(f.catalog.profile.voice()).contains("前段礼貌克制","中段愿意分享","后段亲昵主动");
    }

    @Test
    void longChapterMayReceiveASecondWindowInsideBudget() {
        StringBuilder chapter=new StringBuilder("1、长章\n");
        chapter.append("杜小洛先出现。").append("铺垫".repeat(2500));
        chapter.append("杜小洛后出现。").append("收尾".repeat(200));
        var scan=new PersonaTextScanner().scan(chapter.toString(),"杜小洛");
        assertThat(scan.matched()).isEqualTo(1);
        assertThat(scan.windows()).hasSize(2);
        assertThat(scan.windows().get(0).chapter()).isEqualTo(1);
        assertThat(scan.windows().get(1).chapter()).isEqualTo(1);
        assertThat(scan.windows().get(1).start()).isGreaterThan(scan.windows().get(0).start());
        assertThat(PersonaTextScanner.VERSION).isEqualTo("chapter-stratified-v2");
    }

    @Test
    void traitModePrefersKnownOverUnknownMajority() {
        var unknown=com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits.InteractionStyle.UNKNOWN;
        var calm=com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits.InteractionStyle.CALM;
        assertThat(PersonaImportService.modePreferKnown(List.of(unknown,unknown,calm))).isEqualTo(calm);
        assertThat(PersonaImportService.modePreferKnown(List.of(unknown,unknown,unknown))).isEqualTo(unknown);
    }

    @Test
    void defaultYanhuoDraftIsExcludedFromSwitchableList() throws Exception {
        Fixture f=fixture(false);
        var accepted=f.service.upload(file(),"杜小洛",PersonaImportDtos.Target.DEFAULT_YANHUO,"default-list-hide");
        var status=f.service.get(accepted.importId());
        assertThat(status.status()).isEqualTo("SUCCEEDED");
        // Service list filters DEFAULT drafts; catalog still holds the draft for preview/apply.
        assertThat(f.service.list()).extracting(p->p.id().asString()).doesNotContain(status.candidatePersonaId());
        assertThat(f.service.preview(status.candidatePersonaId()).persona().id().asString()).isEqualTo(status.candidatePersonaId());
    }

    @Test
    void localAcceptanceTxtUsesWholeBookScanAndBoundedSpreadSampling() throws Exception {
        String path=System.getenv("PERSONA_ACCEPTANCE_TXT");
        Assumptions.assumeTrue(path!=null&&!path.isBlank(),"本机验收通过 PERSONA_ACCEPTANCE_TXT 环境变量提供，原文不进仓库");
        byte[] bytes=Files.readAllBytes(Path.of(path));
        Fixture f=fixture(false);
        var accepted=f.service.upload(new MockMultipartFile("file",Path.of(path).getFileName().toString(),"text/plain",bytes),"杜小洛",PersonaImportDtos.Target.DEFAULT_YANHUO,"local-acceptance");
        var status=f.service.get(accepted.importId());
        assertThat(status.status()).as("%s: %s",status.errorCode(),status.errorSummary()).isEqualTo("SUCCEEDED");
        assertThat(status.coverage().scannedChapters()).isEqualTo(384);
        assertThat(status.coverage().modelWindows()).isLessThanOrEqualTo(96);
        assertThat(status.coverage().inputChars()).isLessThanOrEqualTo(320_000);
        assertThat(status.coverage().windows().getFirst().startOffset()).isLessThan(400_000);
        assertThat(status.coverage().windows().getLast().endOffset()).isGreaterThan(1_200_000);
        var quality=f.service.preview(status.candidatePersonaId()).quality();
        assertThat(quality.passed()).as("%s traits=%s evidence=%s",quality.reasons(),f.catalog.profile.defaultOverlayTraits(),f.catalog.profile.evidence().size()).isTrue();
        int[] original=new String(bytes,StandardCharsets.UTF_8).codePoints().toArray();
        assertThat(f.catalog.profile.evidence()).hasSizeGreaterThanOrEqualTo(2);
        for(var evidence:f.catalog.profile.evidence())
            assertThat(new String(original,evidence.startOffset(),evidence.endOffset()-evidence.startOffset())).isEqualTo(evidence.excerpt());
    }

    @Test
    void failedImportResumesFromCheckpointWithoutReplayingDoneBatches() throws Exception {
        StringBuilder book=new StringBuilder();
        for(int chapter=1;chapter<=6;chapter++) {
            book.append(chapter).append("、章节\n").append("铺垫".repeat(1700));
            book.append("杜小洛出现，她说：内容。\n").append("后续".repeat(1700)).append('\n');
        }
        var dataSource=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve(UUID.randomUUID()+".db"));
        var jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE persona_source(source_id TEXT PRIMARY KEY,file_name TEXT,sha256 TEXT,byte_count INTEGER,codepoint_count INTEGER,storage_name TEXT,deleted_at TEXT,created_at TEXT)");
        jdbc.execute("CREATE TABLE persona_import_job(import_id TEXT PRIMARY KEY,request_key TEXT UNIQUE,source_id TEXT,character_hint TEXT,status TEXT,target TEXT NOT NULL DEFAULT 'NEW_PERSONA',progress INTEGER DEFAULT 0,error_code TEXT,error_summary TEXT,model_id TEXT,extraction_version TEXT,scan_chapters INTEGER DEFAULT 0,matched_chapters INTEGER DEFAULT 0,model_chapters INTEGER DEFAULT 0,model_windows INTEGER DEFAULT 0,input_chars INTEGER DEFAULT 0,unmodeled_chapters INTEGER DEFAULT 0,window_offsets_json TEXT DEFAULT '[]',candidate_persona_id TEXT,lease_until TEXT,created_at TEXT,updated_at TEXT)");
        ObjectMapper mapper=new ObjectMapper();
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        ModelPort port=(request,context)->{
            int n=calls.incrementAndGet();
            if(n==3) return new ModelOutcome.Failure("TIMEOUT","timeout",true);
            return new ModelOutcome.FinalAnswer(fakeProfile(request,mapper,false),new ModelUsage(0,0));
        };
        var resolver=mock(EnabledModelPortResolver.class);
        when(resolver.resolve()).thenReturn(new ResolveResult.Resolved(port,"fake-vendor","fake-model"));
        TestCatalog catalog=new TestCatalog();
        var service=new PersonaImportService(jdbc,mapper,catalog,resolver,Runnable::run,new DataSourceTransactionManager(dataSource),temp.toString());
        String requestKey="resume-checkpoint";
        var accepted=service.upload(new MockMultipartFile("file","chapters.txt","text/plain",book.toString().getBytes(StandardCharsets.UTF_8)),"杜小洛",requestKey);
        var failed=service.get(accepted.importId());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("MODEL_FAILED");
        assertThat(failed.coverage().modelWindows()).isGreaterThanOrEqualTo(2);
        assertThat(Files.isRegularFile(temp.resolve("persona/import/checkpoint").resolve(accepted.importId()+".json"))).isTrue();
        int afterFail=calls.get();
        int cachedWindows=failed.coverage().modelWindows();
        var again=service.upload(new MockMultipartFile("file","chapters.txt","text/plain",book.toString().getBytes(StandardCharsets.UTF_8)),"杜小洛",requestKey);
        assertThat(again.importId()).isEqualTo(accepted.importId());
        var status=service.get(accepted.importId());
        assertThat(status.status()).as("%s: %s",status.errorCode(),status.errorSummary()).isEqualTo("SUCCEEDED");
        assertThat(status.coverage().modelWindows()).isEqualTo(6);
        // 续跑只补未完成窗口，不得把已缓存批再打一遍。
        assertThat(calls.get()-afterFail).isEqualTo(6-cachedWindows);
        assertThat(Files.isRegularFile(temp.resolve("persona/import/checkpoint").resolve(accepted.importId()+".json"))).isFalse();
        assertThat(catalog.created).isTrue();
    }

    @Test
    void recoverInterruptedJobsLinksDraftByRequestKeyNotBareSourceId() throws Exception {
        var dataSource=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve(UUID.randomUUID()+".db"));
        var jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE persona_source(source_id TEXT PRIMARY KEY,file_name TEXT,sha256 TEXT,byte_count INTEGER,codepoint_count INTEGER,storage_name TEXT,deleted_at TEXT,created_at TEXT)");
        jdbc.execute("CREATE TABLE persona_import_job(import_id TEXT PRIMARY KEY,request_key TEXT UNIQUE,source_id TEXT,character_hint TEXT,status TEXT,target TEXT NOT NULL DEFAULT 'NEW_PERSONA',progress INTEGER DEFAULT 0,error_code TEXT,error_summary TEXT,model_id TEXT,extraction_version TEXT,scan_chapters INTEGER DEFAULT 0,matched_chapters INTEGER DEFAULT 0,model_chapters INTEGER DEFAULT 0,model_windows INTEGER DEFAULT 0,input_chars INTEGER DEFAULT 0,unmodeled_chapters INTEGER DEFAULT 0,window_offsets_json TEXT DEFAULT '[]',candidate_persona_id TEXT,lease_until TEXT,created_at TEXT,updated_at TEXT)");
        jdbc.execute("CREATE TABLE persona_definition(id TEXT PRIMARY KEY,status TEXT NOT NULL,display_name TEXT NOT NULL,profile_json TEXT NOT NULL,revision INTEGER NOT NULL,source_id TEXT,request_key TEXT UNIQUE,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        String importId=UUID.randomUUID().toString(), sourceId=UUID.randomUUID().toString(), personaId="persona_recovered";
        jdbc.update("INSERT INTO persona_source(source_id,file_name,sha256,byte_count,codepoint_count,storage_name,created_at) VALUES(?,?,?,?,?,?,?)",
                sourceId,"a.txt","x",1,1,sourceId+".txt",Instant.now().toString());
        jdbc.update("INSERT INTO persona_import_job(import_id,request_key,source_id,character_hint,status,target,created_at,updated_at,extraction_version) VALUES(?,?,?,?,?,?,?,?,?)",
                importId,"recover-key",sourceId,"杜小洛","RUNNING","NEW_PERSONA",Instant.now().toString(),Instant.now().toString(),"v");
        jdbc.update("INSERT INTO persona_definition(id,status,display_name,profile_json,revision,source_id,request_key,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                personaId,"DRAFT","杜小洛","{\"schemaVersion\":1,\"displayName\":\"杜小洛\",\"soul\":\"s\",\"voice\":\"v\",\"identity\":\"i\",\"sources\":[],\"evidence\":[]}",1,sourceId,"recover-key",Instant.now().toString(),Instant.now().toString());
        // Unrelated persona sharing only source_id must not win recovery.
        jdbc.update("INSERT INTO persona_definition(id,status,display_name,profile_json,revision,source_id,request_key,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "persona_other","DRAFT","别人","{\"schemaVersion\":1,\"displayName\":\"别人\",\"soul\":\"s\",\"voice\":\"v\",\"identity\":\"i\",\"sources\":[],\"evidence\":[]}",1,sourceId,"other-key",Instant.now().toString(),Instant.now().toString());
        var resolver=mock(EnabledModelPortResolver.class);
        when(resolver.resolve()).thenReturn(new ResolveResult.Rejected("NO","no"));
        var service=new PersonaImportService(jdbc,new ObjectMapper(),new TestCatalog(),resolver,Runnable::run,new DataSourceTransactionManager(dataSource),temp.toString());
        assertThat(service.get(importId).status()).isEqualTo("SUCCEEDED");
        assertThat(service.get(importId).candidatePersonaId()).isEqualTo(personaId);
    }

    @Test
    void failedImportWithSameRequestKeyIsRequeued() throws Exception {
        var dataSource=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve(UUID.randomUUID()+".db"));
        var jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE persona_source(source_id TEXT PRIMARY KEY,file_name TEXT,sha256 TEXT,byte_count INTEGER,codepoint_count INTEGER,storage_name TEXT,deleted_at TEXT,created_at TEXT)");
        jdbc.execute("CREATE TABLE persona_import_job(import_id TEXT PRIMARY KEY,request_key TEXT UNIQUE,source_id TEXT,character_hint TEXT,status TEXT,target TEXT NOT NULL DEFAULT 'NEW_PERSONA',progress INTEGER DEFAULT 0,error_code TEXT,error_summary TEXT,model_id TEXT,extraction_version TEXT,scan_chapters INTEGER DEFAULT 0,matched_chapters INTEGER DEFAULT 0,model_chapters INTEGER DEFAULT 0,model_windows INTEGER DEFAULT 0,input_chars INTEGER DEFAULT 0,unmodeled_chapters INTEGER DEFAULT 0,window_offsets_json TEXT DEFAULT '[]',candidate_persona_id TEXT,lease_until TEXT,created_at TEXT,updated_at TEXT)");
        String importId=UUID.randomUUID().toString(), sourceId=UUID.randomUUID().toString();
        Path sources=temp.resolve("persona/sources");Files.createDirectories(sources);
        Files.writeString(sources.resolve(sourceId+".txt"),"杜小洛笑了。杜小洛递来茶。");
        jdbc.update("INSERT INTO persona_source(source_id,file_name,sha256,byte_count,codepoint_count,storage_name,created_at) VALUES(?,?,?,?,?,?,?)",
                sourceId,"novel.txt","x",20,20,sourceId+".txt",Instant.now().toString());
        jdbc.update("INSERT INTO persona_import_job(import_id,request_key,source_id,character_hint,status,target,error_code,created_at,updated_at,extraction_version) VALUES(?,?,?,?,?,?,?,?,?,?)",
                importId,"retry-key",sourceId,"杜小洛","FAILED","NEW_PERSONA","MODEL_FAILED",Instant.now().toString(),Instant.now().toString(),"v");
        var resolver=mock(EnabledModelPortResolver.class);
        ObjectMapper mapper=new ObjectMapper();
        ModelPort port=(request,context)->new ModelOutcome.FinalAnswer(fakeProfile(request,mapper,false),new ModelUsage(0,0));
        when(resolver.resolve()).thenReturn(new ResolveResult.Resolved(port,"fake-vendor","fake-model"));
        TestCatalog catalog=new TestCatalog();
        var service=new PersonaImportService(jdbc,mapper,catalog,resolver,Runnable::run,new DataSourceTransactionManager(dataSource),temp.toString());
        var again=service.upload(file(),"杜小洛","retry-key");
        assertThat(again.importId()).isEqualTo(importId);
        assertThat(service.get(importId).status()).isEqualTo("SUCCEEDED");
        assertThat(catalog.created).isTrue();
    }

    @Test
    void modelBatchOrderAlternatesBookEndsSoBudgetCoversLateChapters() {
        var windows=java.util.stream.IntStream.range(0,10).mapToObj(i->new PersonaTextScanner.Window(i+1,i*100,(i+1)*100)).toList();
        var ordered=PersonaImportService.spreadModelOrder(windows);
        assertThat(ordered).containsExactly(windows.get(0),windows.get(9),windows.get(1),windows.get(8),windows.get(2),windows.get(7),windows.get(3),windows.get(6),windows.get(4),windows.get(5));
    }

    private Fixture fixture(boolean failure) throws Exception { return fixture(failure,false); }
    private Fixture fixture(boolean failure,boolean forgeEvidence) throws Exception {
        var dataSource=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve(UUID.randomUUID()+".db"));
        var jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE persona_source(source_id TEXT PRIMARY KEY,file_name TEXT,sha256 TEXT,byte_count INTEGER,codepoint_count INTEGER,storage_name TEXT,deleted_at TEXT,created_at TEXT)");
        jdbc.execute("CREATE TABLE persona_import_job(import_id TEXT PRIMARY KEY,request_key TEXT UNIQUE,source_id TEXT,character_hint TEXT,status TEXT,target TEXT NOT NULL DEFAULT 'NEW_PERSONA',progress INTEGER DEFAULT 0,error_code TEXT,error_summary TEXT,model_id TEXT,extraction_version TEXT,scan_chapters INTEGER DEFAULT 0,matched_chapters INTEGER DEFAULT 0,model_chapters INTEGER DEFAULT 0,model_windows INTEGER DEFAULT 0,input_chars INTEGER DEFAULT 0,unmodeled_chapters INTEGER DEFAULT 0,window_offsets_json TEXT DEFAULT '[]',candidate_persona_id TEXT,lease_until TEXT,created_at TEXT,updated_at TEXT)");
        var resolver=mock(EnabledModelPortResolver.class);
        ObjectMapper mapper=new ObjectMapper();
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger outputChars=new java.util.concurrent.atomic.AtomicInteger();
        ModelPort port=(request,context)->failure?new ModelOutcome.Failure("TIMEOUT","timeout",true):new ModelOutcome.FinalAnswer(fakeProfile(request,mapper,forgeEvidence),new ModelUsage(0,0));
        ModelPort measured=(request,context)->{calls.incrementAndGet();ModelOutcome outcome=port.decide(request,context);if(outcome instanceof ModelOutcome.FinalAnswer answer)outputChars.addAndGet(answer.text().length());return outcome;};
        when(resolver.resolve()).thenReturn(new ResolveResult.Resolved(measured,"fake-vendor","fake-model"));
        TestCatalog catalog=new TestCatalog(); Executor direct=Runnable::run;
        var service=new PersonaImportService(jdbc,new ObjectMapper(),catalog,resolver,direct,new DataSourceTransactionManager(dataSource),temp.toString());
        return new Fixture(service,catalog,calls,outputChars);
    }
    private String sourceIdFromRequest(ModelRequest request) {
        String content=request.messages().getLast().content();
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("sourceId:\"([0-9a-f-]{36})\"").matcher(content);
        return match.find()?match.group(1):"missing";
    }
    private String fakeProfile(ModelRequest request,ObjectMapper mapper,boolean forgeEvidence) {
        try {
            String source=sourceIdFromRequest(request), text=request.messages().getLast().content();
            java.util.regex.Matcher windows=java.util.regex.Pattern.compile("章节(\\d+) 偏移(\\d+)-(\\d+)\\n").matcher(text);
            List<PersonaProfileV1.Evidence> evidence=new java.util.ArrayList<>();
            while(windows.find()&&evidence.size()<4) {
                int chapter=Integer.parseInt(windows.group(1));
                int start=Integer.parseInt(windows.group(2)), end=Integer.parseInt(windows.group(3));
                int next=text.indexOf("\n\n章节",windows.end()); if(next<0)next=text.length();
                String content=text.substring(windows.end(),next).stripTrailing(); int[] cps=content.codePoints().toArray();
                boolean added=false;
                for(int at=0;at+2<cps.length;at++) if(cps[at]=='杜'&&cps[at+1]=='小'&&cps[at+2]=='洛') {
                    int len=Math.min(80,cps.length-at);
                    for(int stop=at;stop<at+len;stop++) if(cps[stop]=='。'||cps[stop]=='！'||cps[stop]=='？'||cps[stop]=='!'||cps[stop]=='?') {len=stop-at+1;break;}
                    String quote=new String(cps,at,len);
                    String category=(chapter%2==0?"[SOUL]表现出可观察的人际倾向":"[VOICE]表现出可观察的说话节奏")
                            +" [STYLE:THOUGHTFUL] [PACE:BALANCED] [INITIATIVE:BALANCED] [HUMOR:LIGHT]";
                    evidence.add(new PersonaProfileV1.Evidence(source,start+at,start+at+len,quote,category,false));
                    added=true;
                    break;
                }
                if(added&&chapter==1&&evidence.size()<4) {
                    int first=content.indexOf("杜小洛"), second=content.indexOf("杜小洛",first+3);
                    if(second>=0) {
                        int offset=content.codePointCount(0,second); int[] rest=content.substring(second).codePoints().toArray();
                        int len=Math.min(80,rest.length); for(int stop=0;stop<len;stop++)if(rest[stop]=='。'||rest[stop]=='！'||rest[stop]=='？'){len=stop+1;break;}
                        String quote=new String(rest,0,len);
                        evidence.add(new PersonaProfileV1.Evidence(source,start+offset,start+offset+len,quote,"[VOICE]可观察的说话方式 [STYLE:THOUGHTFUL] [PACE:BALANCED] [INITIATIVE:BALANCED] [HUMOR:LIGHT]",false));
                    }
                }
            }
            java.util.Set<String> styles=new java.util.LinkedHashSet<>();
            java.util.regex.Matcher ranges=java.util.regex.Pattern.compile("章节\\d+ 偏移(\\d+)-(\\d+)\\n").matcher(text);
            while(ranges.find()) {
                int at=Integer.parseInt(ranges.group(1));
                styles.add(at<25_000?"前段礼貌克制":at<55_000?"中段愿意分享":"后段亲昵主动");
            }
            String voice=String.join("；",styles);
            if(forgeEvidence) {
                evidence.clear();
                // 摘录故意与原文不符；即便偏移碰巧落入长章第二窗口，也应被核验拒绝。
                evidence.add(new PersonaProfileV1.Evidence(source,9_000,9_003,"QQQ","伪造为目标人物证据",false));
                evidence.add(new PersonaProfileV1.Evidence(source,9_003,9_006,"ZZZ","伪造为另一项证据",false));
            }
            boolean defaultTarget=request.messages().getLast().content().contains("defaultOverlayTraits");
            var traits=defaultTarget?new com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits(
                    com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits.InteractionStyle.THOUGHTFUL,
                    com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits.ResponsePace.BALANCED,
                    com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits.Initiative.BALANCED,
                    com.wannian.server.kernel.persona.DefaultPersonaOverlayTraits.Humor.LIGHT):null;
            PersonaProfileV1 profile=new PersonaProfileV1(1,"杜小洛","温和而主动",voice,"杜小洛",List.of(new PersonaProfileV1.SourceReference(source,"小说 TXT")),evidence,traits);
            return mapper.writeValueAsString(profile);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private MockMultipartFile file() {
        return new MockMultipartFile("file","novel.txt","text/plain","杜小洛笑了。杜小洛递来茶。".getBytes(StandardCharsets.UTF_8));
    }
    private record Fixture(PersonaImportService service,TestCatalog catalog,java.util.concurrent.atomic.AtomicInteger calls,java.util.concurrent.atomic.AtomicInteger outputChars) {}
    private static final class TestCatalog implements PersonaCatalog {
        PersonaProfileV1 profile; PersonaDefinition persona; boolean created;
        public PersonaDefinition createDraft(PersonaProfileV1 p,String requestKey) { profile=p;created=true;persona=new PersonaDefinition(new PersonaId("persona_test"),p.displayName(),PersonaDefinition.Status.DRAFT,1,p,p.sources().getFirst().sourceId(),Instant.now());return persona; }
        public PersonaDefinition activateDraft(PersonaId id,int rev){throw new UnsupportedOperationException();}
        public Optional<PersonaDefinition> get(PersonaId id){return persona!=null&&persona.id().equals(id)?Optional.of(persona):Optional.empty();}
        public List<PersonaDefinition> list(){return persona==null?List.of():List.of(persona);}
        public PersonaDefinition archive(PersonaId id,int rev){throw new UnsupportedOperationException();}
    }
}
