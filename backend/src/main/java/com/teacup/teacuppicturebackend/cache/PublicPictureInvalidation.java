package com.teacup.teacuppicturebackend.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 公开图片的缓存失效。
 *
 * 只做两件事，顺序固定：先删详情键，再抬版本号。反过来会留下一个本可避免的窗口
 * ——版本号已经抬升，而详情键里仍是旧值。
 *
 * 两个动作各管一层，缺一不可：
 *   版本号负责三个实例的本地缓存与列表共享缓存。这两者无法枚举、也无法被远程精确清理；
 *   删除详情键负责详情共享缓存中的那一条。详情键不含版本号，换号管不到它。
 *
 * 这是有界最终一致，不是强一致。两个动作任一失败都不会造成永久错误，
 * 只会把陈旧时间延长到缓存自身的有效期。因此这里不做重试，也不需要事务发件箱：
 * 缓存本身可以重建，通知丢失只是延迟收敛。
 */
@Slf4j
@Component
public class PublicPictureInvalidation {

    private final StringRedisTemplate redis;
    private final CacheGeneration generation;

    public PublicPictureInvalidation(StringRedisTemplate redis, CacheGeneration generation) {
        this.redis = redis;
        this.generation = generation;
    }

    /**
     * 图片公开状态或公开字段发生变化时调用。
     *
     * 若调用发生在活跃事务中，失效动作会挂到事务提交回调上执行。这一步不能省：
     * 在提交前失效会撞上一个必然发生的竞态——别的请求读到尚未提交的旧值并回填缓存，
     * 事务提交之后那份旧值不会自行纠正。
     *
     * 事务回滚时回调不触发，也就不会做无意义的失效。
     */
    public void pictureChanged(long pictureId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    applyNow(pictureId);
                }
            });
            return;
        }
        applyNow(pictureId);
    }

    private void applyNow(long pictureId) {
        try {
            redis.delete(PublicPictureCacheKeys.detailSharedKey(pictureId));
        } catch (RuntimeException unavailable) {
            log.warn("共享缓存详情键删除失败，将依靠有效期收敛，pictureId={}", pictureId);
        }
        generation.bumpCatalog();
    }
}
