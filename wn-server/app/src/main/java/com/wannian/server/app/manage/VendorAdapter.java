package com.wannian.server.app.manage;

/**
 * 供应商侧适配器：凭证端点上的目录检索。不负责聊天 {@code decide}。
 */
public interface VendorAdapter {

    ListModelsOutcome listModels(VendorRecord vendor);
}
