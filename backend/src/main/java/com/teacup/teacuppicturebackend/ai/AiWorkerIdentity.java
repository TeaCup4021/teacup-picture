package com.teacup.teacuppicturebackend.ai;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 本进程的工作者身份。
 * workerId 格式为「实例 UUID:线程名」，实例 UUID 固定 36 字符，
 * 因此 {@link AiTaskLeaseRenewer} 可以安全地用 instanceId 做前缀匹配批量续租。
 */
@Component
public class AiWorkerIdentity {
    private final String instanceId = UUID.randomUUID().toString();

    public String instanceId() {
        return instanceId;
    }

    public String workerId() {
        return instanceId + ":" + Thread.currentThread().getName();
    }
}
