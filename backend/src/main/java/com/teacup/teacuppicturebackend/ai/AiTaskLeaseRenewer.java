package com.teacup.teacuppicturebackend.ai;

import com.teacup.teacuppicturebackend.mapper.AiTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 在任务执行期间周期性地为本实例持有的全部执行中任务续租。
 * 没有这一层，租约就退化成「一次性超时判断」，会主动误判存活者为死亡。
 */
@Slf4j
@Component
public class AiTaskLeaseRenewer {
    private final AiTaskMapper taskMapper;
    private final AiWorkerIdentity workerIdentity;
    private final long leaseSeconds;

    public AiTaskLeaseRenewer(AiTaskMapper taskMapper, AiWorkerIdentity workerIdentity,
                              @Value("${teacup.ai.worker.lease-seconds:300}") long leaseSeconds,
                              @Value("${teacup.ai.worker.lease-renew-millis:90000}") long leaseRenewMillis) {
        if (leaseSeconds <= 0) throw new IllegalArgumentException("AI worker lease seconds must be positive");
        if (leaseRenewMillis <= 0) throw new IllegalArgumentException("AI worker lease renew millis must be positive");
        // 续租周期必须显著小于租期，否则租约会先于下一次续租到期。
        if (leaseRenewMillis * 3 > leaseSeconds * 1000L) {
            throw new IllegalArgumentException(
                    "AI worker lease-renew-millis (" + leaseRenewMillis + ") must satisfy renew * 3 <= lease-seconds * 1000 ("
                            + leaseSeconds * 1000L + ")");
        }
        this.taskMapper = taskMapper;
        this.workerIdentity = workerIdentity;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(initialDelayString = "${teacup.ai.worker.lease-renew-millis:90000}",
            fixedDelayString = "${teacup.ai.worker.lease-renew-millis:90000}")
    public void renew() {
        try {
            LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(leaseSeconds);
            int renewed = taskMapper.renewLeasesByInstance(workerIdentity.instanceId(), leaseUntil);
            if (renewed > 0) {
                log.debug("Renewed {} AI task leases, instanceId={}", renewed, workerIdentity.instanceId());
            }
        } catch (RuntimeException exception) {
            // 续租失败不改变任何任务状态，下一轮重试即可；租约到期后由恢复服务接管。
            log.warn("Failed to renew AI task leases, instanceId={}", workerIdentity.instanceId(), exception);
        }
    }
}
