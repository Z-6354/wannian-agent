package com.wannian.server.app.manage;

import java.util.List;

/** {@link VendorAdapter#listModels} 的封闭结果。 */
public sealed interface ListModelsOutcome {

    record Listed(List<ModelCatalogEntry> entries) implements ListModelsOutcome {
        public Listed {
            entries = List.copyOf(entries);
        }
    }

    record Rejected(String code, String detail) implements ListModelsOutcome {}
}
