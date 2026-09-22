package com.wannian.server.app.model;

import com.wannian.server.app.model.EnabledModelPortResolver.ResolveResult;
import com.wannian.server.kernel.model.ModelCallContext;
import com.wannian.server.kernel.model.ModelOutcome;
import com.wannian.server.kernel.model.ModelPort;
import com.wannian.server.kernel.model.ModelRequest;
import java.util.Objects;

/**
 * 每次 {@code decide} 时再解析当前启用模型。
 *
 * <p>供单例 {@code DefaultAgentLoop} 使用；未启用时返回受控 Failure，不抛异常。
 * Controller 仍应在 RECEIVED 路径上先检查，以免无谓认领。
 */
public final class ResolvingModelPort implements ModelPort {

    private final EnabledModelPortResolver resolver;

    public ResolvingModelPort(EnabledModelPortResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public ModelOutcome decide(ModelRequest request, ModelCallContext context) {
        return switch (resolver.resolve()) {
            case ResolveResult.Rejected rejected ->
                    new ModelOutcome.Failure(rejected.code(), rejected.detail(), false);
            case ResolveResult.Resolved resolved -> resolved.port().decide(request, context);
        };
    }
}
