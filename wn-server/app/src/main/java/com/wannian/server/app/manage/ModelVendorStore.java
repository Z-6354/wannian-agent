package com.wannian.server.app.manage;

import com.wannian.server.app.manage.ManageBodies.EnabledSelection;
import com.wannian.server.app.manage.ManageBodies.ListedModel;
import com.wannian.server.app.manage.ManageBodies.ModelEntryBody;
import com.wannian.server.app.manage.ManageBodies.VendorBody;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 供应商与已加入模型列表的 SQLite 存储。当前使用是列表行上的 enabled 字段。
 * 检索目录委托 {@link VendorAdapter}，只留在内存。
 */
@Component
public class ModelVendorStore {

    private final DataSource dataSource;
    private final VendorAdapterResolver adapters;
    private final ModelCatalogCache catalogCache;

    public ModelVendorStore(
            DataSource dataSource, VendorAdapterResolver adapters, ModelCatalogCache catalogCache) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.catalogCache = Objects.requireNonNull(catalogCache, "catalogCache");
    }

    public List<VendorBody> list() {
        String sql =
                """
                SELECT id, display_name, protocol, base_url, api_key_env, revision
                FROM model_vendor
                ORDER BY id
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            List<VendorBody> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(readVendor(rs));
            }
            return List.copyOf(rows);
        } catch (SQLException ex) {
            throw new ManagePersistenceException(ex);
        }
    }

    public SaveResult save(String id, ManageBodies.UpsertVendorRequest request) {
        if (!VendorRules.validId(id)) {
            return SaveResult.rejected(ManageReason.ILLEGAL_ARGUMENT, "供应商 id 不合法");
        }
        if (request == null) {
            return SaveResult.rejected(ManageReason.ILLEGAL_ARGUMENT, "请求体不能为空");
        }
        VendorRules.Check check =
                VendorRules.check(request.displayName(), request.protocol(), request.baseUrl(), request.apiKeyEnv());
        if (check instanceof VendorRules.Check.Bad bad) {
            return SaveResult.rejected(bad.code(), bad.detail());
        }
        VendorRules.Check.OkFields ok = (VendorRules.Check.OkFields) check;
        String now = Instant.now().toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Long current = findRevision(connection, id);
                if (current == null) {
                    insert(connection, id, ok, now);
                    connection.commit();
                    catalogCache.invalidate(id);
                    return new SaveResult.Saved(load(connection, id), true);
                }
                if (request.expectedRevision() == null || request.expectedRevision() != current) {
                    connection.rollback();
                    String code =
                            request.expectedRevision() == null
                                    ? ManageReason.ILLEGAL_ARGUMENT
                                    : ManageReason.REVISION_CONFLICT;
                    String detail =
                            request.expectedRevision() == null ? "更新必须带 expectedRevision" : "revision 与库中不一致";
                    return SaveResult.rejected(code, detail);
                }
                int updated = update(connection, id, ok, current, now);
                if (updated != 1) {
                    connection.rollback();
                    return SaveResult.rejected(ManageReason.REVISION_CONFLICT, "revision 与库中不一致");
                }
                connection.commit();
                catalogCache.invalidate(id);
                return new SaveResult.Saved(load(connection, id), false);
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                throw new ManagePersistenceException(ex);
            } catch (RuntimeException ex) {
                rollbackQuietly(connection);
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new ManagePersistenceException(ex);
        }
    }

    public DeleteResult delete(String id) {
        if (!VendorRules.validId(id)) {
            return new DeleteResult.Rejected(ManageReason.ILLEGAL_ARGUMENT, "供应商 id 不合法");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (findRevision(connection, id) == null) {
                    connection.rollback();
                    return new DeleteResult.Rejected(ManageReason.VENDOR_NOT_FOUND, "供应商不存在");
                }
                try (PreparedStatement clearListed =
                        connection.prepareStatement("DELETE FROM model_listed WHERE vendor_id = ?")) {
                    clearListed.setString(1, id);
                    clearListed.executeUpdate();
                }
                try (PreparedStatement drop = connection.prepareStatement("DELETE FROM model_vendor WHERE id = ?")) {
                    drop.setString(1, id);
                    if (drop.executeUpdate() != 1) {
                        connection.rollback();
                        return new DeleteResult.Rejected(ManageReason.VENDOR_NOT_FOUND, "供应商不存在");
                    }
                }
                connection.commit();
                catalogCache.invalidate(id);
                return new DeleteResult.Deleted();
            } catch (SQLException | RuntimeException ex) {
                rollbackQuietly(connection);
                if (ex instanceof SQLException sql) {
                    throw new ManagePersistenceException(sql);
                }
                throw (RuntimeException) ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new ManagePersistenceException(ex);
        }
    }

    public ListResult listModels(String id) {
        if (!VendorRules.validId(id)) {
            return new ListResult.Rejected(ManageReason.ILLEGAL_ARGUMENT, "供应商 id 不合法");
        }
        try (Connection connection = dataSource.getConnection()) {
            VendorRecord vendor = findVendor(connection, id);
            if (vendor == null) {
                return new ListResult.Rejected(ManageReason.VENDOR_NOT_FOUND, "供应商不存在");
            }
            VendorAdapter adapter = adapters.resolve(vendor.protocol());
            if (adapter == null) {
                return new ListResult.Rejected(ManageReason.PROTOCOL_UNSUPPORTED, "该供应商协议不能检索目录");
            }
            return switch (adapter.listModels(vendor)) {
                case ListModelsOutcome.Listed listed -> {
                    catalogCache.put(id, listed.entries());
                    yield new ListResult.Listed(
                            id,
                            listed.entries().stream()
                                    .map(entry -> new ModelEntryBody(entry.id(), entry.displayName()))
                                    .toList());
                }
                case ListModelsOutcome.Rejected rejected ->
                        new ListResult.Rejected(rejected.code(), rejected.detail());
            };
        } catch (SQLException ex) {
            throw new ManagePersistenceException(ex);
        }
    }

    public EnabledSelection findEnabled() {
        String sql = "SELECT vendor_id, model_id FROM model_listed WHERE enabled = 1";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return new EnabledSelection(rs.getString(1), rs.getString(2));
        } catch (SQLException ex) {
            throw new ManagePersistenceException(ex);
        }
    }

    /** 当前使用模型及完整供应商端点；无人启用时返回 null。 */
    public EnabledBinding findEnabledBinding() {
        EnabledSelection selection = findEnabled();
        if (selection == null) {
            return null;
        }
        try (Connection connection = dataSource.getConnection()) {
            VendorRecord vendor = findVendor(connection, selection.vendorId());
            if (vendor == null) {
                return null;
            }
            return new EnabledBinding(vendor, selection.modelId());
        } catch (SQLException ex) {
            throw new ManagePersistenceException(ex);
        }
    }

    public record EnabledBinding(VendorRecord vendor, String modelId) {}

    public List<ListedModel> listListed() {
        String sql =
                """
                SELECT vendor_id, model_id, display_name, enabled
                FROM model_listed
                ORDER BY enabled DESC, added_at, vendor_id, model_id
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            List<ListedModel> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(
                        new ListedModel(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getInt(4) == 1));
            }
            return List.copyOf(rows);
        } catch (SQLException ex) {
            throw new ManagePersistenceException(ex);
        }
    }

    /** 只把目录里选中的一条写入已加入列表。整份检索结果仍只留在内存缓存。 */
    public AddResult addListed(String vendorId, String modelId) {
        if (!VendorRules.validId(vendorId)) {
            return new AddResult.Rejected(ManageReason.ILLEGAL_ARGUMENT, "供应商 id 不合法");
        }
        if (modelId == null || modelId.isBlank()) {
            return new AddResult.Rejected(ManageReason.ILLEGAL_ARGUMENT, "模型 id 不能为空");
        }
        try (Connection connection = dataSource.getConnection()) {
            VendorRecord vendor = findVendor(connection, vendorId);
            if (vendor == null) {
                return new AddResult.Rejected(ManageReason.VENDOR_NOT_FOUND, "供应商不存在");
            }
            if (adapters.resolve(vendor.protocol()) == null) {
                return new AddResult.Rejected(ManageReason.PROTOCOL_UNSUPPORTED, "该供应商协议不能检索目录");
            }
            Lookup lookup = lookupEphemeral(vendor, modelId);
            if (lookup.failure() != null) {
                return lookup.failure();
            }
            if (lookup.entry() == null) {
                return new AddResult.Rejected(ManageReason.MODEL_NOT_IN_CATALOG, "模型不在本次检索目录中");
            }
            ModelCatalogEntry entry = lookup.entry();
            String sql =
                    """
                    INSERT INTO model_listed (vendor_id, model_id, display_name, enabled, added_at)
                    VALUES (?, ?, ?, 0, ?)
                    ON CONFLICT(vendor_id, model_id) DO UPDATE SET
                        display_name = excluded.display_name
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, vendorId);
                ps.setString(2, modelId);
                ps.setString(3, entry.displayName());
                ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
            }
            boolean enabled = isListedEnabled(connection, vendorId, modelId);
            return new AddResult.Added(new ListedModel(vendorId, modelId, entry.displayName(), enabled));
        } catch (SQLException ex) {
            throw new ManagePersistenceException(ex);
        }
    }

    public RemoveResult removeListed(String vendorId, String modelId) {
        if (!VendorRules.validId(vendorId) || modelId == null || modelId.isBlank()) {
            return new RemoveResult.Rejected(ManageReason.ILLEGAL_ARGUMENT, "供应商或模型 id 不合法");
        }
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "DELETE FROM model_listed WHERE vendor_id = ? AND model_id = ?")) {
                ps.setString(1, vendorId);
                ps.setString(2, modelId);
                if (ps.executeUpdate() == 0) {
                    return new RemoveResult.Rejected(ManageReason.MODEL_NOT_LISTED, "模型不在已加入列表中");
                }
            }
            return new RemoveResult.Removed();
        } catch (SQLException ex) {
            throw new ManagePersistenceException(ex);
        }
    }

    private Lookup lookupEphemeral(VendorRecord vendor, String modelId) {
        VendorAdapter adapter = adapters.resolve(vendor.protocol());
        if (adapter == null) {
            return new Lookup(null, new AddResult.Rejected(ManageReason.PROTOCOL_UNSUPPORTED, "该供应商协议不能检索目录"));
        }
        List<ModelCatalogEntry> entries = catalogCache.get(vendor.id()).orElse(null);
        if (entries == null) {
            ListModelsOutcome catalog = adapter.listModels(vendor);
            if (catalog instanceof ListModelsOutcome.Rejected rejected) {
                return new Lookup(null, new AddResult.Rejected(rejected.code(), rejected.detail()));
            }
            entries = ((ListModelsOutcome.Listed) catalog).entries();
            catalogCache.put(vendor.id(), entries);
        }
        for (ModelCatalogEntry entry : entries) {
            if (modelId.equals(entry.id())) {
                return new Lookup(entry, null);
            }
        }
        return new Lookup(null, null);
    }

    private record Lookup(ModelCatalogEntry entry, AddResult.Rejected failure) {}

    public EnableResult enable(String vendorId, String modelId) {
        if (!VendorRules.validId(vendorId)) {
            return new EnableResult.Rejected(ManageReason.ILLEGAL_ARGUMENT, "供应商 id 不合法");
        }
        if (modelId == null || modelId.isBlank()) {
            return new EnableResult.Rejected(ManageReason.ILLEGAL_ARGUMENT, "模型 id 不能为空");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (findVendor(connection, vendorId) == null) {
                    connection.rollback();
                    return new EnableResult.Rejected(ManageReason.VENDOR_NOT_FOUND, "供应商不存在");
                }
                if (!isListed(connection, vendorId, modelId)) {
                    connection.rollback();
                    return new EnableResult.Rejected(ManageReason.MODEL_NOT_LISTED, "模型不在已加入列表中");
                }
                try (PreparedStatement clear =
                        connection.prepareStatement("UPDATE model_listed SET enabled = 0 WHERE enabled = 1")) {
                    clear.executeUpdate();
                }
                try (PreparedStatement set =
                        connection.prepareStatement(
                                """
                                UPDATE model_listed
                                SET enabled = 1
                                WHERE vendor_id = ? AND model_id = ?
                                """)) {
                    set.setString(1, vendorId);
                    set.setString(2, modelId);
                    if (set.executeUpdate() != 1) {
                        connection.rollback();
                        return new EnableResult.Rejected(ManageReason.MODEL_NOT_LISTED, "模型不在已加入列表中");
                    }
                }
                connection.commit();
                return new EnableResult.Enabled(new EnabledSelection(vendorId, modelId));
            } catch (SQLException | RuntimeException ex) {
                rollbackQuietly(connection);
                if (ex instanceof SQLException sql) {
                    throw new ManagePersistenceException(sql);
                }
                throw (RuntimeException) ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new ManagePersistenceException(ex);
        }
    }

    private static boolean isListed(Connection connection, String vendorId, String modelId) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT 1 FROM model_listed WHERE vendor_id = ? AND model_id = ?")) {
            ps.setString(1, vendorId);
            ps.setString(2, modelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean isListedEnabled(Connection connection, String vendorId, String modelId)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT enabled FROM model_listed WHERE vendor_id = ? AND model_id = ?")) {
            ps.setString(1, vendorId);
            ps.setString(2, modelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private static VendorBody load(Connection connection, String id) throws SQLException {
        String sql =
                """
                SELECT id, display_name, protocol, base_url, api_key_env, revision
                FROM model_vendor WHERE id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("刚写入的供应商读不回来");
                }
                return readVendor(rs);
            }
        }
    }

    private static VendorBody readVendor(ResultSet rs) throws SQLException {
        return new VendorBody(
                rs.getString("id"),
                rs.getString("display_name"),
                rs.getString("protocol"),
                rs.getString("base_url"),
                rs.getString("api_key_env"),
                rs.getLong("revision"));
    }

    private static Long findRevision(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT revision FROM model_vendor WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getLong(1);
            }
        }
    }

    private static VendorRecord findVendor(Connection connection, String id) throws SQLException {
        String sql = "SELECT id, protocol, base_url, api_key_env FROM model_vendor WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new VendorRecord(
                        rs.getString("id"),
                        rs.getString("protocol"),
                        rs.getString("base_url"),
                        rs.getString("api_key_env"));
            }
        }
    }

    private static void insert(Connection connection, String id, VendorRules.Check.OkFields ok, String now)
            throws SQLException {
        String sql =
                """
                INSERT INTO model_vendor (
                    id, display_name, protocol, base_url, api_key_env, revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, ok.displayName());
            ps.setString(3, StubModelCatalog.PROTOCOL);
            ps.setString(4, ok.baseUrl());
            ps.setString(5, ok.apiKeyEnv());
            ps.setString(6, now);
            ps.setString(7, now);
            ps.executeUpdate();
        }
    }

    private static int update(
            Connection connection, String id, VendorRules.Check.OkFields ok, long expectedRevision, String now)
            throws SQLException {
        String sql =
                """
                UPDATE model_vendor
                SET display_name = ?, protocol = ?, base_url = ?, api_key_env = ?,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND revision = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ok.displayName());
            ps.setString(2, StubModelCatalog.PROTOCOL);
            ps.setString(3, ok.baseUrl());
            ps.setString(4, ok.apiKeyEnv());
            ps.setString(5, now);
            ps.setString(6, id);
            ps.setLong(7, expectedRevision);
            return ps.executeUpdate();
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 调用方只看原失败。
        }
    }

    public sealed interface SaveResult permits SaveResult.Saved, SaveResult.Rejected {
        record Saved(VendorBody vendor, boolean created) implements SaveResult {}

        record Rejected(String code, String detail) implements SaveResult {}

        static SaveResult rejected(String code, String detail) {
            return new Rejected(code, detail);
        }
    }

    public sealed interface DeleteResult permits DeleteResult.Deleted, DeleteResult.Rejected {
        record Deleted() implements DeleteResult {}

        record Rejected(String code, String detail) implements DeleteResult {}
    }

    public sealed interface ListResult permits ListResult.Listed, ListResult.Rejected {
        record Listed(String vendorId, List<ModelEntryBody> entries) implements ListResult {}

        record Rejected(String code, String detail) implements ListResult {}
    }

    public sealed interface EnableResult permits EnableResult.Enabled, EnableResult.Rejected {
        record Enabled(EnabledSelection selection) implements EnableResult {}

        record Rejected(String code, String detail) implements EnableResult {}
    }

    public sealed interface AddResult permits AddResult.Added, AddResult.Rejected {
        record Added(ListedModel model) implements AddResult {}

        record Rejected(String code, String detail) implements AddResult {}
    }

    public sealed interface RemoveResult permits RemoveResult.Removed, RemoveResult.Rejected {
        record Removed() implements RemoveResult {}

        record Rejected(String code, String detail) implements RemoveResult {}
    }

    static final class ManagePersistenceException extends RuntimeException {
        ManagePersistenceException(SQLException cause) {
            super(cause);
        }
    }
}
