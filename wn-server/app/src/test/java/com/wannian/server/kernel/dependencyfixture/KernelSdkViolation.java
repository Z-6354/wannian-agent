package com.wannian.server.kernel.dependencyfixture;

import com.openai.fixture.SdkMarker;

/** 隔离负例：kernel 引用厂商 SDK。 */
public final class KernelSdkViolation {
    public SdkMarker marker;
}
