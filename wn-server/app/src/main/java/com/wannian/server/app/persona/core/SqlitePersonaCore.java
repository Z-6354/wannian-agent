package com.wannian.server.app.persona.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.ConversationStatus;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.app.persona.PersonaDataPaths;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.persona.*;
import com.wannian.server.kernel.tool.RoleId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Core-owned SQLite implementation of the shared persona contracts. */
@Component
public final class SqlitePersonaCore implements PersonaCatalog, ConversationPersonaBinding, PendingConversationPersonaSwitch, DefaultPersonaOverlayPort {
    private static final System.Logger LOG=System.getLogger(SqlitePersonaCore.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PersonaProfileV1 DEFAULT_PROFILE = new PersonaProfileV1(1,
            "杜小洛，保持原有稳定人格。", "读取 data-dir/prompts/VOICE.md。", "杜小洛", List.of(), List.of());
    private final DataSource dataSource;
    private final Path dataDir;
    private final com.wannian.server.app.persona.PersonaOverlayLimits overlayLimits;

    @Autowired
    public SqlitePersonaCore(
            DataSource dataSource,
            @Value("${wannian.data-dir:data}") String dataDir,
            @Value("${wannian.persona.overlay-layer-char-budget:1000}") int overlayLayerChars,
            @Value("${wannian.persona.overlay-total-char-budget:1600}") int overlayTotalChars,
            @Value("${wannian.persona.import-window-chars:3000}") int importWindowChars) {
        this(
                dataSource,
                com.wannian.server.app.persistence.SqliteConfig.resolveDataDir(dataDir),
                new com.wannian.server.app.persona.PersonaOverlayLimits(
                        overlayLayerChars, overlayTotalChars, importWindowChars));
    }

    /** Test/embedding constructor; production uses the configured data directory above. */
    public SqlitePersonaCore(DataSource dataSource) {
        this(dataSource, Path.of("data").toAbsolutePath().normalize(), com.wannian.server.app.persona.PersonaOverlayLimits.DEFAULT);
    }

    public SqlitePersonaCore(DataSource dataSource, Path dataDir) {
        this(dataSource, dataDir, com.wannian.server.app.persona.PersonaOverlayLimits.DEFAULT);
    }

    public SqlitePersonaCore(
            DataSource dataSource, Path dataDir, com.wannian.server.app.persona.PersonaOverlayLimits overlayLimits) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.dataDir = Objects.requireNonNull(dataDir).toAbsolutePath().normalize();
        this.overlayLimits = Objects.requireNonNull(overlayLimits, "overlayLimits");
        try {
            PersonaDataPaths.migrateLegacyLayout(this.dataDir);
        } catch (IOException ex) {
            throw new IllegalStateException("PERSONA_DATA_LAYOUT_MIGRATE_FAILED", ex);
        }
    }

    @Override public PersonaDefinition createDraft(PersonaProfileV1 profile, String requestKey) {
        Objects.requireNonNull(profile); if (requestKey == null || requestKey.isBlank() || requestKey.length()>160) throw new IllegalArgumentException("requestKey 非法");
        try (Connection c=dataSource.getConnection()) {
            try (PreparedStatement q=c.prepareStatement("SELECT * FROM persona_definition WHERE request_key=?")) {
                q.setString(1,requestKey); try(ResultSet rs=q.executeQuery()){ if(rs.next()) return readDefinition(rs); }
            }
            String id="persona_"+UUID.randomUUID(); String now=Instant.now().toString();
            try(PreparedStatement s=c.prepareStatement("INSERT INTO persona_definition(id,status,display_name,profile_json,revision,source_id,request_key,created_at,updated_at) VALUES(?,?,?,?,1,?,?,?,?)")) {
                s.setString(1,id); s.setString(2,PersonaDefinition.Status.DRAFT.name()); s.setString(3,profile.displayName()); s.setString(4,JSON.writeValueAsString(profile)); s.setString(5,profile.sources().isEmpty()?null:profile.sources().getFirst().sourceId()); s.setString(6,requestKey); s.setString(7,now); s.setString(8,now); s.executeUpdate();
            }
            return new PersonaDefinition(new PersonaId(id),profile.displayName(),PersonaDefinition.Status.DRAFT,1,profile,profile.sources().isEmpty()?null:profile.sources().getFirst().sourceId(),Instant.parse(now));
        } catch(Exception ex) { throw failure("创建角色草稿失败",ex); }
    }
    @Override public PersonaDefinition activateDraft(PersonaId id,int expectedRevision) { return changeStatus(id,expectedRevision,PersonaDefinition.Status.DRAFT,PersonaDefinition.Status.ACTIVE); }
    @Override public PersonaDefinition archive(PersonaId id,int expectedRevision) {
        if(id.equals(PersonaId.YANHUO)) throw new IllegalArgumentException("yanhuo 不可归档");
        try(Connection c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            // Acquire SQLite's writer lock before checking/rewriting bindings.
            try(PreparedStatement lock=c.prepareStatement("UPDATE persona_definition SET updated_at=updated_at WHERE id=?")){lock.setString(1,id.value());lock.executeUpdate();}
            String now=Instant.now().toString();
            // Doc allows either refuse-or-rebind; rebind keeps no dangling ACTIVE bindings.
            try(PreparedStatement s=c.prepareStatement("UPDATE conversation_persona SET persona_id='yanhuo',revision=revision+1,updated_at=? WHERE persona_id=?")){
                s.setString(1,now);s.setString(2,id.value());s.executeUpdate();
            }
            try(PreparedStatement s=c.prepareStatement("UPDATE persona_definition SET status='ARCHIVED',revision=revision+1,updated_at=? WHERE id=? AND revision=? AND status IN ('ACTIVE','DRAFT')")) {
                s.setString(1,now);s.setString(2,id.value());s.setInt(3,expectedRevision);
                if(s.executeUpdate()!=1){c.rollback();throw new IllegalStateException("REVISION_CONFLICT:角色状态或 revision 已变化");}
            }
            PersonaDefinition value=getOn(c,id).orElseThrow();c.commit();return value;
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw failure("归档角色失败",ex);}
    }
    private PersonaDefinition changeStatus(PersonaId id,int expected,PersonaDefinition.Status from,PersonaDefinition.Status to) {
        try(Connection c=dataSource.getConnection()) { c.setAutoCommit(false);
            try(PreparedStatement s=c.prepareStatement("UPDATE persona_definition SET status=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND status=?")) {
                s.setString(1,to.name()); s.setString(2,Instant.now().toString()); s.setString(3,id.value()); s.setInt(4,expected); s.setString(5,from.name());
                if(s.executeUpdate()!=1) { c.rollback(); throw new IllegalStateException("REVISION_CONFLICT:角色状态或 revision 已变化"); }
            }
            PersonaDefinition value=getOn(c,id).orElseThrow(); c.commit(); return value;
        } catch(RuntimeException ex){throw ex;} catch(Exception ex){throw failure("更新角色状态失败",ex);}
    }
    @Override public Optional<PersonaDefinition> get(PersonaId id) {
        if(id.equals(PersonaId.YANHUO)) return Optional.of(defaultDefinition());
        try(Connection c=dataSource.getConnection()) {return getOn(c,id);} catch(Exception ex){throw failure("读取角色失败",ex);}
    }
    @Override public List<PersonaDefinition> list() {
        List<PersonaDefinition> out=new ArrayList<>(); out.add(defaultDefinition());
        try(Connection c=dataSource.getConnection(); PreparedStatement s=c.prepareStatement("SELECT * FROM persona_definition WHERE id<>'yanhuo' ORDER BY created_at,id"); ResultSet rs=s.executeQuery()) {while(rs.next())out.add(readDefinition(rs));return List.copyOf(out);} catch(Exception ex){throw failure("列出角色失败",ex);}
    }
    @Override public BindingSnapshot bind(ConversationId conversationId, PersonaId personaId, long expected) {
        try(Connection c=dataSource.getConnection()) { c.setAutoCommit(false);
            // Serialize status validation and binding write against concurrent archive.
            try(PreparedStatement lock=c.prepareStatement("UPDATE conversation SET updated_at=updated_at WHERE id=?")){lock.setString(1,conversationId.asString());if(lock.executeUpdate()!=1)throw new IllegalStateException("CONVERSATION_NOT_FOUND");}
            try(PreparedStatement state=c.prepareStatement("SELECT status FROM conversation WHERE id=?")){state.setString(1,conversationId.asString());try(ResultSet rs=state.executeQuery()){if(!rs.next())throw new IllegalStateException("CONVERSATION_NOT_FOUND");if(!ConversationStatus.ACTIVE.name().equals(rs.getString(1)))throw new IllegalStateException("CONVERSATION_NOT_ACTIVE");}}
            try(PreparedStatement q=c.prepareStatement("SELECT status FROM persona_definition WHERE id=?")){q.setString(1,personaId.value());try(ResultSet rs=q.executeQuery()){if(!rs.next())throw new IllegalArgumentException("PERSONA_NOT_FOUND");if(!PersonaDefinition.Status.ACTIVE.name().equals(rs.getString(1)))throw new IllegalArgumentException("PERSONA_NOT_ACTIVE");}}
            try(PreparedStatement q=c.prepareStatement("SELECT 1 FROM turn t WHERE t.conversation_id=? AND (t.status IN ('CLAIMED','RUNNING','COMMITTING') OR (t.status='RECEIVED' AND EXISTS (SELECT 1 FROM turn_persona tp WHERE tp.turn_id=t.id))) LIMIT 1")){q.setString(1,conversationId.asString());try(ResultSet rs=q.executeQuery()){if(rs.next())throw new IllegalStateException("TURN_ACTIVE");}}
            long actual=0; String current=null;
            try(PreparedStatement q=c.prepareStatement("SELECT persona_id,revision FROM conversation_persona WHERE conversation_id=?")){q.setString(1,conversationId.asString());try(ResultSet rs=q.executeQuery()){if(rs.next()){current=rs.getString(1);actual=rs.getLong(2);}}}
            if(actual!=expected){c.rollback();throw new IllegalStateException("REVISION_CONFLICT:actual="+actual);}
            long next=actual+1; String now=Instant.now().toString();
            if(current==null){try(PreparedStatement s=c.prepareStatement("INSERT INTO conversation_persona(conversation_id,persona_id,revision,updated_at) VALUES(?,?,?,?)")){s.setString(1,conversationId.asString());s.setString(2,personaId.value());s.setLong(3,next);s.setString(4,now);s.executeUpdate();}}
            else {try(PreparedStatement s=c.prepareStatement("UPDATE conversation_persona SET persona_id=?,revision=?,updated_at=? WHERE conversation_id=? AND revision=?")){s.setString(1,personaId.value());s.setLong(2,next);s.setString(3,now);s.setString(4,conversationId.asString());s.setLong(5,actual);if(s.executeUpdate()!=1){c.rollback();throw new IllegalStateException("REVISION_CONFLICT");}}}
            c.commit();return new BindingSnapshot(conversationId,personaId,next);
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){
            String detail=ex.getMessage()==null?"":ex.getMessage().toLowerCase(Locale.ROOT);
            if(detail.contains("busy")||detail.contains("locked")||detail.contains("unique constraint")||detail.contains("constraint failed")) throw new IllegalStateException("REVISION_CONFLICT:绑定已被并发更新",ex);
            throw failure("切换会话角色失败",ex);
        }
    }
    @Override public PersonaTurnSnapshot resolveForTurn(ConversationId conversationId, TurnId turnId) {
        IllegalStateException lastBusy=null;
        for(int attempt=0;attempt<12;attempt++) {
            try {
                return resolveForTurnOnce(conversationId,turnId);
            } catch(IllegalStateException ex) {
                if(!"PERSONA_RESOLVE_BUSY".equals(ex.getMessage())) throw ex;
                lastBusy=ex;
                try { Thread.sleep(5L+attempt*5L); } catch(InterruptedException ie){Thread.currentThread().interrupt();throw failure("解析回合角色失败",ie);}
            }
        }
        throw failure("解析回合角色失败",lastBusy);
    }

    private PersonaTurnSnapshot resolveForTurnOnce(ConversationId conversationId, TurnId turnId) {
        try(Connection c=dataSource.getConnection()) {
            try(Statement pragma=c.createStatement()){pragma.execute("PRAGMA busy_timeout=3000");}
            c.setAutoCommit(false);
            try(PreparedStatement q=c.prepareStatement("SELECT persona_id,definition_revision,binding_revision,snapshot_json FROM turn_persona WHERE turn_id=?")){q.setString(1,turnId.asString());try(ResultSet rs=q.executeQuery()){if(rs.next()){PersonaTurnSnapshot saved=JSON.readValue(rs.getString(4),PersonaTurnSnapshot.class);c.commit();return saved;}}}
            String id="yanhuo";long binding=0;
            try(PreparedStatement q=c.prepareStatement("SELECT status FROM turn WHERE id=? AND conversation_id=?")){q.setString(1,turnId.asString());q.setString(2,conversationId.asString());try(ResultSet rs=q.executeQuery()){if(!rs.next())throw new IllegalArgumentException("TURN_NOT_FOUND");String status=rs.getString(1);
                // Live/recovery paths freeze the current binding. Terminal turns without a snapshot stay yanhuo (legacy).
                boolean useCurrentBinding="RECEIVED".equals(status)||"CLAIMED".equals(status)||"RUNNING".equals(status)||"COMMITTING".equals(status);
                if(useCurrentBinding) try(PreparedStatement b=c.prepareStatement("SELECT persona_id,revision FROM conversation_persona WHERE conversation_id=?")){b.setString(1,conversationId.asString());try(ResultSet br=b.executeQuery()){if(br.next()){id=br.getString(1);binding=br.getLong(2);}}}
            }}
            PersonaDefinition d;
            if("yanhuo".equals(id)) d=defaultDefinition(); else d=getOn(c,new PersonaId(id)).orElseGet(this::defaultDefinition);
            if(d.status()==PersonaDefinition.Status.ARCHIVED||!id.equals(d.id().value())) {
                // Stale binding to archived/missing persona: repair session and freeze yanhuo.
                repairBindingToYanhuo(c,conversationId);
                d=defaultDefinition();id="yanhuo";binding=bindingRevision(c,conversationId);
            }
            CompanionIdentity ci=id.equals("yanhuo")?CompanionIdentity.YANHUO:new CompanionIdentity(id);
            OverlayState overlay = id.equals("yanhuo") ? snapshotOverlayOrEmpty(c) : OverlayState.empty();
            PersonaTurnSnapshot snap=new PersonaTurnSnapshot(new PersonaId(id),d.revision(),binding,d.profile().soul(),d.profile().voice(),d.profile().identity(),ci,RoleId.YANHUO,overlay.revision(),overlay.soul(),overlay.voice());
            try(PreparedStatement s=c.prepareStatement("INSERT OR IGNORE INTO turn_persona(turn_id,conversation_id,persona_id,definition_revision,binding_revision,snapshot_json,created_at) VALUES(?,?,?,?,?,?,?)")){
                s.setString(1,turnId.asString());s.setString(2,conversationId.asString());s.setString(3,id);s.setInt(4,d.revision());s.setLong(5,binding);s.setString(6,JSON.writeValueAsString(snap));s.setString(7,Instant.now().toString());
                s.executeUpdate();
            }
            // Always re-read: concurrent first claim must share the winner's immutable snapshot.
            try(PreparedStatement q=c.prepareStatement("SELECT snapshot_json FROM turn_persona WHERE turn_id=?")){
                q.setString(1,turnId.asString());
                try(ResultSet rs=q.executeQuery()){
                    if(!rs.next()) throw new SQLException("turn_persona missing after insert");
                    PersonaTurnSnapshot saved=JSON.readValue(rs.getString(1),PersonaTurnSnapshot.class);
                    c.commit();
                    return saved;
                }
            }
        }catch(IllegalArgumentException ex){throw ex;}
        catch(Exception ex){
            if(isBusy(ex)) throw new IllegalStateException("PERSONA_RESOLVE_BUSY",ex);
            throw failure("解析回合角色失败",ex);
        }
    }
    @Override public BindingSnapshot current(ConversationId conversationId) {
        try(Connection c=dataSource.getConnection(); PreparedStatement q=c.prepareStatement("SELECT persona_id,revision FROM conversation_persona WHERE conversation_id=?")) {
            q.setString(1,conversationId.asString()); try(ResultSet rs=q.executeQuery()){if(rs.next())return new BindingSnapshot(conversationId,new PersonaId(rs.getString(1)),rs.getLong(2));}
            try(PreparedStatement exists=c.prepareStatement("SELECT 1 FROM conversation WHERE id=?")){exists.setString(1,conversationId.asString());try(ResultSet rs=exists.executeQuery()){if(!rs.next())throw new IllegalArgumentException("CONVERSATION_NOT_FOUND");}}
            return new BindingSnapshot(conversationId,PersonaId.YANHUO,0);
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw failure("读取会话角色失败",ex);}
    }
    @Override public Optional<PersonaId> personaIdForTurn(TurnId turnId) {
        try(Connection c=dataSource.getConnection();PreparedStatement q=c.prepareStatement("SELECT persona_id FROM turn_persona WHERE turn_id=?")){
            q.setString(1,turnId.asString());try(ResultSet rs=q.executeQuery()){return Optional.of(rs.next()?new PersonaId(rs.getString(1)):PersonaId.YANHUO);}
        }catch(Exception ex){throw failure("读取回合角色失败",ex);}
    }
    @Override public void schedule(ConversationId conversationId,PersonaId personaId,long expectedBindingRevision,TurnId sourceTurnId,String operationId) {
        Objects.requireNonNull(conversationId);Objects.requireNonNull(personaId);Objects.requireNonNull(sourceTurnId);
        if(operationId==null||operationId.isBlank())throw new IllegalArgumentException("operationId 非法");
        try(Connection c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            try(PreparedStatement prior=c.prepareStatement("SELECT conversation_id,persona_id,expected_binding_revision,source_turn_id FROM pending_persona_switch WHERE operation_id=?")) {
                prior.setString(1,operationId);try(ResultSet rs=prior.executeQuery()){if(rs.next()) {
                    boolean same=conversationId.asString().equals(rs.getString(1))&&personaId.value().equals(rs.getString(2))&&expectedBindingRevision==rs.getLong(3)&&sourceTurnId.asString().equals(rs.getString(4));
                    c.commit();if(same)return;throw new IllegalStateException("PERSONA_SWITCH_OPERATION_CONFLICT");
                }}
            }
            try(PreparedStatement prior=c.prepareStatement("SELECT operation_id,conversation_id,persona_id,expected_binding_revision FROM pending_persona_switch WHERE source_turn_id=?")) {
                prior.setString(1,sourceTurnId.asString());try(ResultSet rs=prior.executeQuery()){if(rs.next()) {
                    boolean same=operationId.equals(rs.getString(1))&&conversationId.asString().equals(rs.getString(2))&&personaId.value().equals(rs.getString(3))&&expectedBindingRevision==rs.getLong(4);
                    c.commit();if(same)return;throw new IllegalStateException("PERSONA_SWITCH_TURN_CONFLICT");
                }}
            }
            lockConversation(c,conversationId);
            try(PreparedStatement turn=c.prepareStatement("SELECT status,conversation_id FROM turn WHERE id=?")){turn.setString(1,sourceTurnId.asString());try(ResultSet rs=turn.executeQuery()){if(!rs.next())throw new IllegalArgumentException("TURN_NOT_FOUND");if(!conversationId.asString().equals(rs.getString(2))||!TurnStatus.RUNNING.name().equals(rs.getString(1)))throw new IllegalStateException("PERSONA_SWITCH_TURN_NOT_RUNNING");}}
            try(PreparedStatement persona=c.prepareStatement("SELECT status FROM persona_definition WHERE id=?")){persona.setString(1,personaId.value());try(ResultSet rs=persona.executeQuery()){if(!rs.next())throw new IllegalArgumentException("PERSONA_NOT_FOUND");if(!PersonaDefinition.Status.ACTIVE.name().equals(rs.getString(1)))throw new IllegalStateException("PERSONA_NOT_ACTIVE");}}
            long actual=bindingRevision(c,conversationId);
            if(actual!=expectedBindingRevision)throw new IllegalStateException("REVISION_CONFLICT:actual="+actual);
            String now=Instant.now().toString();
            try(PreparedStatement insert=c.prepareStatement("INSERT INTO pending_persona_switch(source_turn_id,conversation_id,persona_id,expected_binding_revision,operation_id,status,created_at,updated_at) VALUES(?,?,?,?,?,'PENDING',?,?)")){
                insert.setString(1,sourceTurnId.asString());insert.setString(2,conversationId.asString());insert.setString(3,personaId.value());insert.setLong(4,expectedBindingRevision);insert.setString(5,operationId);insert.setString(6,now);insert.setString(7,now);insert.executeUpdate();
            }
            c.commit();
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw failure("排队会话角色切换失败",ex);}
    }
    @Override public void turnCompleted(ConversationId conversationId,TurnId turnId){finishPendingSwitch(conversationId,turnId,true);}
    @Override public void turnAborted(ConversationId conversationId,TurnId turnId){finishPendingSwitch(conversationId,turnId,false);}
    @Override public void recoverTerminalTurns(){
        List<SwitchJob> jobs=new ArrayList<>();
        try(Connection c=dataSource.getConnection();PreparedStatement q=c.prepareStatement("SELECT p.conversation_id,p.source_turn_id,t.status FROM pending_persona_switch p JOIN turn t ON t.id=p.source_turn_id WHERE p.status='PENDING' AND t.status IN ('COMPLETED','FAILED','CANCELLED')");ResultSet rs=q.executeQuery()){
            while(rs.next())jobs.add(new SwitchJob(new ConversationId(UUID.fromString(rs.getString(1))),new TurnId(UUID.fromString(rs.getString(2))),rs.getString(3).equals("COMPLETED")));
        }catch(Exception ex){throw failure("恢复待处理会话角色切换失败",ex);}
        for(SwitchJob job:jobs)finishPendingSwitch(job.conversationId(),job.turnId(),job.completed());
    }
    private void finishPendingSwitch(ConversationId conversationId,TurnId turnId,boolean completed) {
        try(Connection c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            try(PreparedStatement request=c.prepareStatement("SELECT persona_id,expected_binding_revision,status,conversation_id FROM pending_persona_switch WHERE source_turn_id=?")) {
                request.setString(1,turnId.asString());try(ResultSet rs=request.executeQuery()) {
                    if(!rs.next()){c.commit();return;}
                    String personaId=rs.getString(1);long expected=rs.getLong(2);String status=rs.getString(3);String savedConversation=rs.getString(4);
                    if(!conversationId.asString().equals(savedConversation)){c.rollback();throw new IllegalStateException("PERSONA_SWITCH_CONVERSATION_MISMATCH");}
                    if(!"PENDING".equals(status)){c.commit();return;}
                    String turnStatus;
                    try(PreparedStatement t=c.prepareStatement("SELECT status FROM turn WHERE id=? AND conversation_id=?")){t.setString(1,turnId.asString());t.setString(2,conversationId.asString());try(ResultSet tr=t.executeQuery()){if(!tr.next()){c.commit();return;}turnStatus=tr.getString(1);}}
                    if(completed&&!TurnStatus.COMPLETED.name().equals(turnStatus)){c.commit();return;}
                    if(!completed&&!(TurnStatus.FAILED.name().equals(turnStatus)||TurnStatus.CANCELLED.name().equals(turnStatus))){c.commit();return;}
                    if(!completed){setPendingSwitchStatus(c,turnId,"ABORTED");c.commit();return;}
                    lockConversation(c,conversationId);
                    try(PreparedStatement p=c.prepareStatement("SELECT status FROM persona_definition WHERE id=?")){p.setString(1,personaId);try(ResultSet pr=p.executeQuery()){if(!pr.next()||!PersonaDefinition.Status.ACTIVE.name().equals(pr.getString(1))){setPendingSwitchStatus(c,turnId,"CONFLICT");c.commit();return;}}}
                    long actual=bindingRevision(c,conversationId);
                    if(actual!=expected){setPendingSwitchStatus(c,turnId,"CONFLICT");c.commit();return;}
                    String now=Instant.now().toString();
                    if(actual==0){try(PreparedStatement s=c.prepareStatement("INSERT INTO conversation_persona(conversation_id,persona_id,revision,updated_at) VALUES(?,?,1,?)")){s.setString(1,conversationId.asString());s.setString(2,personaId);s.setString(3,now);s.executeUpdate();}}
                    else {try(PreparedStatement s=c.prepareStatement("UPDATE conversation_persona SET persona_id=?,revision=revision+1,updated_at=? WHERE conversation_id=? AND revision=?")){s.setString(1,personaId);s.setString(2,now);s.setString(3,conversationId.asString());s.setLong(4,actual);if(s.executeUpdate()!=1){setPendingSwitchStatus(c,turnId,"CONFLICT");c.commit();return;}}}
                    setPendingSwitchStatus(c,turnId,"APPLIED");c.commit();
                }
            }
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw failure("应用会话角色切换失败",ex);}
    }
    private static void setPendingSwitchStatus(Connection c,TurnId turnId,String status)throws SQLException{try(PreparedStatement s=c.prepareStatement("UPDATE pending_persona_switch SET status=?,updated_at=? WHERE source_turn_id=? AND status='PENDING'")){s.setString(1,status);s.setString(2,Instant.now().toString());s.setString(3,turnId.asString());s.executeUpdate();}}
    private static void repairBindingToYanhuo(Connection c,ConversationId conversationId)throws SQLException{
        try(PreparedStatement q=c.prepareStatement("SELECT persona_id,revision FROM conversation_persona WHERE conversation_id=?")){
            q.setString(1,conversationId.asString());
            try(ResultSet rs=q.executeQuery()){
                if(!rs.next()||"yanhuo".equals(rs.getString(1))) return;
                long rev=rs.getLong(2);String now=Instant.now().toString();
                try(PreparedStatement u=c.prepareStatement("UPDATE conversation_persona SET persona_id='yanhuo',revision=?,updated_at=? WHERE conversation_id=? AND revision=?")){
                    u.setLong(1,rev+1);u.setString(2,now);u.setString(3,conversationId.asString());u.setLong(4,rev);u.executeUpdate();
                }
            }
        }
    }
    private static long bindingRevision(Connection c,ConversationId conversationId)throws SQLException{try(PreparedStatement q=c.prepareStatement("SELECT revision FROM conversation_persona WHERE conversation_id=?")){q.setString(1,conversationId.asString());try(ResultSet rs=q.executeQuery()){return rs.next()?rs.getLong(1):0;}}}
    private static void lockConversation(Connection c,ConversationId conversationId)throws SQLException{try(PreparedStatement lock=c.prepareStatement("UPDATE conversation SET updated_at=updated_at WHERE id=?")){lock.setString(1,conversationId.asString());if(lock.executeUpdate()!=1)throw new IllegalStateException("CONVERSATION_NOT_FOUND");}}
    private record SwitchJob(ConversationId conversationId,TurnId turnId,boolean completed){}

    @Override public synchronized DefaultPersonaOverlayRevision apply(PersonaDefinition draft,long expectedOverlayRevision,String operationId) {
        Objects.requireNonNull(draft,"validatedDraft");
        if(draft.status()!=PersonaDefinition.Status.DRAFT)throw new IllegalArgumentException("DEFAULT_OVERLAY_REQUIRES_DRAFT");
        if(draft.id().equals(PersonaId.YANHUO))throw new IllegalArgumentException("DEFAULT_OVERLAY_SOURCE_MUST_BE_DRAFT");
        if(draft.profile().defaultOverlayTraits()==null)throw new IllegalArgumentException("DEFAULT_OVERLAY_TRAITS_REQUIRED");
        validateOverlayTraitEvidence(draft.profile());
        DefaultPersonaOverlayRenderer.Rendered rendered=DefaultPersonaOverlayRenderer.render(draft.profile().defaultOverlayTraits());
        String soul=rendered.soul(),voice=rendered.voice();
        if(soul.length()+voice.length()>overlayLimits.totalCharBudget())throw new IllegalArgumentException("DEFAULT_OVERLAY_TOO_LONG");
        if(operationId==null||operationId.isBlank()||operationId.length()>160)throw new IllegalArgumentException("OPERATION_ID_INVALID");
        ensureInitialPromptBackup();
        try(Connection c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            try(PreparedStatement prior=c.prepareStatement("SELECT revision,source_persona_id,source_profile_revision,soul_overlay,voice_overlay FROM yanhuo_profile_overlay_revision WHERE operation_id=?")) {
                prior.setString(1,operationId);try(ResultSet rs=prior.executeQuery()){if(rs.next()) {
                    boolean same=rs.getString(2).equals(draft.id().value())&&rs.getInt(3)==draft.revision()&&rs.getString(4).equals(soul)&&rs.getString(5).equals(voice);
                    DefaultPersonaOverlayRevision saved=readOverlay(c,rs.getLong(1));c.commit();if(same)return saved;throw new IllegalStateException("DEFAULT_OVERLAY_OPERATION_CONFLICT");
                }}
            }
            PersonaDefinition stored=getOn(c,draft.id()).orElseThrow(()->new IllegalArgumentException("DEFAULT_OVERLAY_DRAFT_NOT_FOUND"));
            if(stored.status()!=PersonaDefinition.Status.DRAFT||stored.revision()!=draft.revision()||!stored.profile().equals(draft.profile()))
                throw new IllegalArgumentException("DEFAULT_OVERLAY_DRAFT_NOT_VALIDATED");
            long actual=overlayRevision(c);
            if(actual!=expectedOverlayRevision)throw new IllegalStateException("DEFAULT_OVERLAY_REVISION_CONFLICT:actual="+actual);
            long next;
            try(Statement q=c.createStatement();ResultSet rs=q.executeQuery("SELECT COALESCE(MAX(revision),0)+1 FROM yanhuo_profile_overlay_revision")){rs.next();next=rs.getLong(1);}
            String evidenceSummary=evidenceSummary(draft.profile());String now=Instant.now().toString();
            try(PreparedStatement s=c.prepareStatement("INSERT INTO yanhuo_profile_overlay_revision(revision,source_persona_id,source_profile_revision,soul_overlay,voice_overlay,evidence_summary,operation_id,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
                s.setLong(1,next);s.setString(2,draft.id().value());s.setInt(3,draft.revision());s.setString(4,soul);s.setString(5,voice);s.setString(6,evidenceSummary);s.setString(7,operationId);s.setString(8,now);s.executeUpdate();
            }
            try(PreparedStatement s=c.prepareStatement("UPDATE yanhuo_profile_overlay_pointer SET revision=? WHERE singleton_id=1 AND COALESCE(revision,0)=?")){s.setLong(1,next);s.setLong(2,actual);if(s.executeUpdate()!=1)throw new IllegalStateException("DEFAULT_OVERLAY_REVISION_CONFLICT");}
            DefaultPersonaOverlayRevision result=readOverlay(c,next);c.commit();return result;
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw failure("应用默认角色 overlay 失败",ex);}
    }

    @Override public Optional<DefaultPersonaOverlayRevision> current() {
        try(Connection c=dataSource.getConnection()) {OverlayState state=currentOverlay(c);return state.revision()==0?Optional.empty():Optional.of(readOverlay(c,state.revision()));}
        catch(Exception ex){throw failure("读取默认角色 overlay 失败",ex);}
    }

    @Override public Optional<DefaultPersonaOverlayRevision> rollback(long revision,long expectedCurrentRevision) {
        if(revision<0)throw new IllegalArgumentException("OVERLAY_REVISION_INVALID");
        try(Connection c=dataSource.getConnection()) {
            c.setAutoCommit(false);long actual=overlayRevision(c);
            if(actual!=expectedCurrentRevision)throw new IllegalStateException("DEFAULT_OVERLAY_REVISION_CONFLICT:actual="+actual);
            if(revision>0)try(PreparedStatement q=c.prepareStatement("SELECT 1 FROM yanhuo_profile_overlay_revision WHERE revision=?")){q.setLong(1,revision);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw new IllegalArgumentException("DEFAULT_OVERLAY_REVISION_NOT_FOUND");}}
            try(PreparedStatement s=c.prepareStatement("UPDATE yanhuo_profile_overlay_pointer SET revision=? WHERE singleton_id=1 AND COALESCE(revision,0)=?")){if(revision==0)s.setNull(1,Types.INTEGER);else s.setLong(1,revision);s.setLong(2,actual);if(s.executeUpdate()!=1)throw new IllegalStateException("DEFAULT_OVERLAY_REVISION_CONFLICT");}
            Optional<DefaultPersonaOverlayRevision> result=revision==0?Optional.empty():Optional.of(readOverlay(c,revision));c.commit();return result;
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw failure("回滚默认角色 overlay 失败",ex);}
    }

    private static long overlayRevision(Connection c)throws SQLException {try(Statement q=c.createStatement();ResultSet rs=q.executeQuery("SELECT COALESCE(revision,0) FROM yanhuo_profile_overlay_pointer WHERE singleton_id=1")){if(!rs.next())throw new SQLException("overlay pointer missing");return rs.getLong(1);}}
    private static OverlayState currentOverlay(Connection c)throws SQLException {long revision=overlayRevision(c);if(revision==0)return OverlayState.empty();try(PreparedStatement q=c.prepareStatement("SELECT soul_overlay,voice_overlay FROM yanhuo_profile_overlay_revision WHERE revision=?")){q.setLong(1,revision);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw new SQLException("overlay revision missing");return new OverlayState(revision,rs.getString(1),rs.getString(2));}}}
    private static OverlayState snapshotOverlayOrEmpty(Connection c) {
        try{return currentOverlay(c);}catch(Exception ex){LOG.log(System.Logger.Level.WARNING,"读取默认角色overlay失败；本回合降级为空overlay",ex);return OverlayState.empty();}
    }
    private static DefaultPersonaOverlayRevision readOverlay(Connection c,long revision)throws Exception {try(PreparedStatement q=c.prepareStatement("SELECT source_persona_id,soul_overlay,voice_overlay,evidence_summary,created_at FROM yanhuo_profile_overlay_revision WHERE revision=?")){q.setLong(1,revision);try(ResultSet rs=q.executeQuery()){if(!rs.next())throw new IllegalArgumentException("DEFAULT_OVERLAY_REVISION_NOT_FOUND");return new DefaultPersonaOverlayRevision(revision,new PersonaId(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getString(4),Instant.parse(rs.getString(5)));}}}
    private static String evidenceSummary(PersonaProfileV1 profile){long certain=profile.evidence().stream().filter(e->!e.uncertain()).count();long uncertain=profile.evidence().size()-certain;return "画像证据记录："+profile.evidence().size()+"项（标记明确 "+certain+"，不确定 "+uncertain+"）；来源 "+profile.sources().size()+"项。未在此保存原文摘录。";}
    private static void validateOverlayTraitEvidence(PersonaProfileV1 profile) {
        DefaultPersonaOverlayTraits traits=profile.defaultOverlayTraits();
        int active=0;
        if(traits.interactionStyle()!=DefaultPersonaOverlayTraits.InteractionStyle.UNKNOWN){active++;requireTraitEvidence(profile,"[STYLE:"+traits.interactionStyle().name()+"]");}
        if(traits.responsePace()!=DefaultPersonaOverlayTraits.ResponsePace.UNKNOWN){active++;requireTraitEvidence(profile,"[PACE:"+traits.responsePace().name()+"]");}
        if(traits.initiative()!=DefaultPersonaOverlayTraits.Initiative.UNKNOWN){active++;requireTraitEvidence(profile,"[INITIATIVE:"+traits.initiative().name()+"]");}
        if(traits.humor()!=DefaultPersonaOverlayTraits.Humor.UNKNOWN){active++;requireTraitEvidence(profile,"[HUMOR:"+traits.humor().name()+"]");}
        if(active<2)throw new IllegalArgumentException("DEFAULT_OVERLAY_INSUFFICIENT_TRAITS");
    }
    private static void requireTraitEvidence(PersonaProfileV1 profile,String marker) {
        Set<String> sourceIds=new HashSet<>();for(PersonaProfileV1.SourceReference source:profile.sources())sourceIds.add(source.sourceId());
        Set<String> witnesses=new HashSet<>();
        for(PersonaProfileV1.Evidence evidence:profile.evidence())if(sourceIds.contains(evidence.sourceId())&&evidence.inference().contains(marker))witnesses.add(evidence.sourceId()+":"+evidence.startOffset());
        if(witnesses.size()<2)throw new IllegalArgumentException("DEFAULT_OVERLAY_TRAIT_EVIDENCE_REQUIRED:"+marker);
    }
    private void ensureInitialPromptBackup() {
        Path prompts=dataDir.resolve("prompts"), root=PersonaDataPaths.overlayBackups(dataDir);Path backup=root.resolve("yanhuo-initial");
        try {
            rejectSymlink(dataDir);rejectSymlink(prompts);rejectSymlink(root);rejectSymlink(backup);
            if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS)){validateBackup(backup);return;}
            Files.createDirectories(root);ownerOnly(root,true);Path staging=Files.createTempDirectory(root,".yanhuo-initial-");ownerOnly(staging,true);boolean moved=false;
            try {
                StringBuilder manifest=new StringBuilder("format=1\n");
                for(String name:List.of("SOUL.md","VOICE.md","IDENTITY.md")) {
                    Path source=prompts.resolve(name);if(Files.isSymbolicLink(source)||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("prompt file missing or unsafe: "+name);
                    byte[] bytes=Files.readAllBytes(source);Path copy=staging.resolve(name);Files.write(copy,bytes,StandardOpenOption.CREATE_NEW);ownerOnly(copy,false);manifest.append(name).append('=').append(sha256(bytes)).append('\n');
                }
                Path sums=staging.resolve("SHA256SUMS");Files.writeString(sums,manifest,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);ownerOnly(sums,false);
                try{Files.move(staging,backup,StandardCopyOption.ATOMIC_MOVE);ownerOnly(backup,true);moved=true;}catch(FileAlreadyExistsException race){validateBackup(backup);}
                catch(AtomicMoveNotSupportedException ex){try{Files.move(staging,backup);ownerOnly(backup,true);moved=true;}catch(FileAlreadyExistsException race){validateBackup(backup);}}
            } finally {if(!moved&&Files.exists(staging))deleteTree(staging);}
        }catch(Exception ex){throw failure("备份默认角色原提示词失败；overlay 未应用",ex);}
    }
    private static void validateBackup(Path backup)throws Exception {
        rejectSymlink(backup);Path sums=backup.resolve("SHA256SUMS");if(Files.isSymbolicLink(sums)||!Files.isRegularFile(sums,LinkOption.NOFOLLOW_LINKS))throw new IOException("backup manifest missing");
        Map<String,String> expected=new HashMap<>();for(String line:Files.readAllLines(sums,StandardCharsets.UTF_8)){int i=line.indexOf('=');if(i>0)expected.put(line.substring(0,i),line.substring(i+1));}
        for(String name:List.of("SOUL.md","VOICE.md","IDENTITY.md")){Path file=backup.resolve(name);if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!Objects.equals(expected.get(name),sha256(Files.readAllBytes(file))))throw new IOException("prompt backup verification failed: "+name);}
    }
    private static void rejectSymlink(Path path)throws IOException {if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&Files.isSymbolicLink(path))throw new IOException("symbolic link is not allowed: "+path);}
    private static void ownerOnly(Path path,boolean directory)throws IOException {try{Set<PosixFilePermission> permissions=directory?Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE):Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);Files.setPosixFilePermissions(path,permissions);}catch(UnsupportedOperationException ignored){/* On Windows the copy inherits the private data-dir ACL. */}}
    private static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void deleteTree(Path root)throws IOException {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private record OverlayState(long revision,String soul,String voice){static OverlayState empty(){return new OverlayState(0,"","");}}
    private PersonaDefinition defaultDefinition(){return new PersonaDefinition(PersonaId.YANHUO,"杜小洛",PersonaDefinition.Status.ACTIVE,0,DEFAULT_PROFILE,null,Instant.EPOCH);}
    private Optional<PersonaDefinition> getOn(Connection c,PersonaId id)throws Exception{try(PreparedStatement s=c.prepareStatement("SELECT * FROM persona_definition WHERE id=?")){s.setString(1,id.value());try(ResultSet rs=s.executeQuery()){return rs.next()?Optional.of(readDefinition(rs)):Optional.empty();}}}
    private static PersonaDefinition readDefinition(ResultSet rs)throws Exception{return new PersonaDefinition(new PersonaId(rs.getString("id")),rs.getString("display_name"),PersonaDefinition.Status.valueOf(rs.getString("status")),rs.getInt("revision"),JSON.readValue(rs.getString("profile_json"),PersonaProfileV1.class),rs.getString("source_id"),Instant.parse(rs.getString("updated_at")));}
    private static boolean isBusy(Throwable ex) {
        for(Throwable cur=ex;cur!=null;cur=cur.getCause()) {
            String detail=cur.getMessage()==null?"":cur.getMessage().toLowerCase(Locale.ROOT);
            if(detail.contains("busy")||detail.contains("locked")||detail.contains("sqlite_busy")) return true;
        }
        return false;
    }
    private static RuntimeException failure(String message,Exception ex){return new IllegalStateException(message,ex);}
}
