package com.wannian.server.app.manage;

import java.util.List;
import java.util.Optional;

/**
 * 供应商预设来源。本批仅 {@link BuiltinVendorPresetSource}；后续可加 Custom 实现并复合。
 */
public interface VendorPresetSource {

    List<VendorPreset> list();

    Optional<VendorPreset> find(String id);
}
