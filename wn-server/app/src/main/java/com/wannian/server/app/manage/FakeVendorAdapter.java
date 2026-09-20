package com.wannian.server.app.manage;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 默认测试与 {@code wannian.model.mode=fake} 使用的目录。不访问网络，也不读密钥。
 */
@Component
public class FakeVendorAdapter implements VendorAdapter {

    @Override
    public ListModelsOutcome listModels(VendorRecord vendor) {
        if (vendor == null || !StubModelCatalog.PROTOCOL.equals(vendor.protocol())) {
            return new ListModelsOutcome.Rejected(ManageReason.PROTOCOL_UNSUPPORTED, "当前只接受 openai-compatible");
        }
        List<ModelCatalogEntry> entries =
                StubModelCatalog.entries().stream()
                        .map(entry -> new ModelCatalogEntry(entry.id(), entry.displayName()))
                        .toList();
        return new ListModelsOutcome.Listed(entries);
    }
}
