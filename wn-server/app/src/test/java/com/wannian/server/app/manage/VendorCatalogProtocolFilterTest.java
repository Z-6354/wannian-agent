package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VendorCatalogProtocolFilterTest {

    @Test
    void responsesKeepsCodexFamilyOnly() {
        List<ModelCatalogEntry> filtered =
                VendorCatalogProtocolFilter.filter(
                        StubModelCatalog.PROTOCOL_RESPONSES,
                        List.of(
                                new ModelCatalogEntry("gpt-6-luna", "gpt-6-luna (codex)"),
                                new ModelCatalogEntry(
                                        "gemini-3.8-flash-high", "gemini-3.8-flash-high (openai)"),
                                new ModelCatalogEntry("deepseek-v4-pro", "deepseek-v4-pro (deepseek)")));
        assertThat(filtered).extracting(ModelCatalogEntry::id).containsExactly("gpt-6-luna");
    }

    @Test
    void chatKeepsNonCodexTagged() {
        List<ModelCatalogEntry> filtered =
                VendorCatalogProtocolFilter.filter(
                        StubModelCatalog.PROTOCOL,
                        List.of(
                                new ModelCatalogEntry("gpt-6-luna", "gpt-6-luna (codex)"),
                                new ModelCatalogEntry(
                                        "gemini-3.8-flash-high", "gemini-3.8-flash-high (openai)"),
                                new ModelCatalogEntry("deepseek-v4-pro", "deepseek-v4-pro (deepseek)")));
        assertThat(filtered)
                .extracting(ModelCatalogEntry::id)
                .containsExactly("gemini-3.8-flash-high", "deepseek-v4-pro");
    }

    @Test
    void untaggedNativeCatalogUnchanged() {
        List<ModelCatalogEntry> filtered =
                VendorCatalogProtocolFilter.filter(
                        StubModelCatalog.PROTOCOL,
                        List.of(
                                new ModelCatalogEntry("deepseek-chat", "deepseek-chat"),
                                new ModelCatalogEntry("deepseek-reasoner", "deepseek-reasoner")));
        assertThat(filtered).hasSize(2);
    }
}
