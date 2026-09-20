package com.wannian.server.app.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.app.model.OpenAiCompatibleModelAdapter;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelRequest;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 公网验收：目录与一次短对话都打真实厂商。密钥只从环境变量读取，断言不包含密钥。
 * 本机未设置 {@code DEEPSEEK_API_KEY} 时跳过，避免无密钥的构建误伤。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class PublicVendorLiveTest {

    @Test
    void deepseekListsModelsAndAnswers() {
        VendorRecord vendor =
                new VendorRecord(
                        "deepseek",
                        StubModelCatalog.PROTOCOL,
                        "https://api.deepseek.com/v1",
                        "DEEPSEEK_API_KEY");
        OpenAiCompatibleVendorAdapter catalog =
                new OpenAiCompatibleVendorAdapter(
                        HttpClient.newHttpClient(), new ObjectMapper(), System::getenv);

        ListModelsOutcome listed = catalog.listModels(vendor);
        assertThat(listed).isInstanceOf(ListModelsOutcome.Listed.class);
        assertThat(((ListModelsOutcome.Listed) listed).entries())
                .extracting(ModelCatalogEntry::id)
                .contains("deepseek-flash");

        OpenAiCompatibleModelAdapter model =
                new OpenAiCompatibleModelAdapter(
                        vendor, "deepseek-flash", HttpClient.newHttpClient(), new ObjectMapper(), System::getenv);
        ModelOutcome outcome =
                model.decide(
                        new ModelRequest(List.of(new ModelMessage("user", "只回复一个字：好"))),
                        new ModelCallContext("public-live", 1, Instant.now().plusSeconds(30), false, "public-live"));

        assertThat(outcome).isInstanceOf(ModelOutcome.FinalAnswer.class);
        assertThat(((ModelOutcome.FinalAnswer) outcome).text()).isNotBlank();
    }
}
