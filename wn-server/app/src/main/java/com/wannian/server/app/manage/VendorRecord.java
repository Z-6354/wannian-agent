package com.wannian.server.app.manage;

/**
 * 已保存的供应商端点。密钥只存环境变量名，适配器在调用时读取变量值。
 */
public record VendorRecord(String id, String protocol, String baseUrl, String apiKeyEnv) {}
