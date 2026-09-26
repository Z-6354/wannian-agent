package com.wannian.server.app.model;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelMessage;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import com.wannian.server.kernel.model.ModelStreamObserver;
import com.wannian.server.kernel.model.ModelUsage;
import java.util.List;

/** 默认测试与 fake 模式：不访问网络。不宣称真流式验收。 */
public final class FakeModelAdapter implements ModelPort {

    private final String modelId;

    public FakeModelAdapter(String modelId) {
        this.modelId = modelId == null ? "fake" : modelId;
    }

    @Override
    public ModelOutcome decide(ModelRequest request, ModelCallContext context) {
        return decide(request, context, ModelStreamObserver.NOOP);
    }

    @Override
    public ModelOutcome decide(
            ModelRequest request, ModelCallContext context, ModelStreamObserver observer) {
        if (context != null && context.isCancelledNow()) {
            return new ModelOutcome.Failure(ErrorCodes.CANCELLED, "调用已取消", false);
        }
        String lastUser = lastUserText(request.messages());
        String text = "假模型(" + modelId + ")：" + lastUser;
        ModelStreamObserver sink = observer == null ? ModelStreamObserver.NOOP : observer;
        try {
            sink.onTextDelta(text);
        } catch (RuntimeException ignored) {
        }
        return new ModelOutcome.FinalAnswer(text, new ModelUsage(0, 0));
    }

    private static String lastUserText(List<ModelMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ModelMessage message = messages.get(i);
            if ("user".equalsIgnoreCase(message.role())) {
                return message.content();
            }
        }
        return "";
    }
}
