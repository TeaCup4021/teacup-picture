package com.teacup.teacuppicturebackend.mapper;

import com.teacup.teacuppicturebackend.model.entity.Space;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 空间 Mapper 接口
 * </p>
 *
 * @author wolves
 * @since 2025-09-27
 */
public interface SpaceMapper extends BaseMapper<Space> {
    @Select("SELECT id FROM space WHERE id = #{spaceId} AND isDelete = 0 FOR UPDATE")
    Long lockActiveById(@Param("spaceId") long spaceId);

    @Delete("DELETE FROM space WHERE id = #{spaceId}")
    int purgeById(@Param("spaceId") long spaceId);

    /**
     * 空间额度收支的统一实现。四个方法的约束都是同一条：
     * <p>
     * <b>已用 + 在途预留 + 本次 &lt;= 上限</b>
     * <p>
     * 条件写在 WHERE 里，由数据库对空间行加排他锁保证并发串行，影响行数为 0 即额度不足。
     * 若拆成「先读、再判、再写」，并发请求会各自读到同一个旧值并同时通过校验，
     * 结果是空间被超额占用——每一行的数值依然正确，被破坏的只是额度规则本身。
     * <p>
     * 「在途预留」（{@code reservedSize}）用于上传会话：会话从创建到完成之间可能持续数小时，
     * 这段时间声明的大小必须先占住，否则用户可以并发开出多个会话把空间撑爆。
     * <p>
     * 新增额度收支点时一律调用这组方法，不要在调用点自己拼 SQL——
     * 「新增一条写入路径时忘记校验」是这个模块唯一的错误来源。
     */

    /**
     * 占用已用额度（上传成功、另存图片、版本替换等）。
     *
     * @param size  本次占用的字节数；传负数表示释放（例如替换成更小的图）
     * @param count 本次占用的图片数，传 0 表示不涉及数量维度
     * @return 额度是否足够并被成功扣减
     */
    default boolean tryConsume(long spaceId, long size, long count) {
        if (spaceId <= 0) {
            return false;
        }
        if (size == 0 && count == 0) {
            return true;
        }
        UpdateWrapper<Space> wrapper = new UpdateWrapper<Space>().eq("id", spaceId);
        if (size != 0) {
            wrapper.setSql("totalSize = totalSize + {0}", size)
                    .apply("totalSize + reservedSize + {0} <= maxSize", size)
                    .apply("totalSize + {0} >= 0", size);
        }
        if (count != 0) {
            wrapper.setSql("totalCount = totalCount + {0}", count)
                    .apply("totalCount + {0} <= maxCount", count)
                    .apply("totalCount + {0} >= 0", count);
        }
        // 必须取影响行数：额度不足时 WHERE 不成立，影响 0 行。
        // 若改用 IService.update(Wrapper)，只能拿到 boolean，无法区分「扣减成功」与「条件不成立」。
        return update(null, wrapper) > 0;
    }

    /**
     * 占用「在途预留」额度：创建上传会话时调用，把声明的大小先占住。
     *
     * @param size 本次预留的字节数，必须为正
     * @return 可用额度是否足够并被成功预留
     */
    default boolean reserve(long spaceId, long size) {
        if (spaceId <= 0 || size <= 0) {
            return false;
        }
        return update(null, new UpdateWrapper<Space>()
                .eq("id", spaceId)
                .setSql("reservedSize = reservedSize + {0}", size)
                .apply("totalSize + reservedSize + {0} <= maxSize", size)) > 0;
    }

    /**
     * 把在途预留结转为已用：上传完成时调用，一条语句里同时释放预留、写入已用与件数。
     * <p>
     * 正常情况不会失败——预留时已经保证 {@code totalSize + reservedSize + reserved <= maxSize}，
     * 而实际大小恒等于声明大小。这里仍然带上限条件作为防御：万一不变量被破坏，
     * 宁可这一次结转失败，也不要让空间超额。
     * <p>
     * 结转失败时必须把预留放掉，否则额度会被永久占住、用户空间凭空少一块。
     *
     * @param reserved 建会话时预留的字节数
     * @param actual   实际写入的字节数（当前实现下恒等于 reserved）
     * @param count    本次新增的图片数
     */
    default boolean settle(long spaceId, long reserved, long actual, long count) {
        if (spaceId <= 0) {
            return false;
        }
        if (reserved == 0 && actual == 0 && count == 0) {
            return true;
        }
        boolean done = update(null, new UpdateWrapper<Space>()
                .eq("id", spaceId)
                .setSql("reservedSize = reservedSize - {0}", reserved)
                .setSql("totalSize = totalSize + {0}", actual)
                .setSql("totalCount = totalCount + {0}", count)
                .apply("reservedSize >= {0}", reserved)
                .apply("totalSize + {0} <= maxSize", actual)
                .apply("totalCount + {0} <= maxCount", count)) > 0;
        if (!done) {
            releaseReservation(spaceId, reserved);
        }
        return done;
    }

    /**
     * 释放「在途预留」：取消上传、会话过期清理时调用。
     * <p>
     * 上传完成走 {@link #settle}，不要在这里重复释放。
     */
    default void releaseReservation(long spaceId, long size) {
        if (spaceId <= 0 || size <= 0) {
            return;
        }
        update(null, new UpdateWrapper<Space>()
                .eq("id", spaceId)
                .setSql("reservedSize = GREATEST(0, reservedSize - {0})", size));
    }

    /**
     * 扣减已用额度（删除图片、丢弃生成图等纯释放场景）。
     * <p>
     * 与 {@link #tryConsume} 传负数的区别：释放不需要检查上限，也不该因为额度算错就阻止删除，
     * 所以这里保底夹到 0 而不是抛错，避免负数扩散出去。
     *
     * @param size  要释放的字节数，必须为正
     * @param count 要释放的图片数，必须为正
     */
    default void releaseUsage(long spaceId, long size, long count) {
        if (spaceId <= 0 || (size <= 0 && count <= 0)) {
            return;
        }
        UpdateWrapper<Space> wrapper = new UpdateWrapper<Space>().eq("id", spaceId);
        if (size > 0) {
            wrapper.setSql("totalSize = GREATEST(0, totalSize - {0})", size);
        }
        if (count > 0) {
            wrapper.setSql("totalCount = GREATEST(0, totalCount - {0})", count);
        }
        update(null, wrapper);
    }

}
