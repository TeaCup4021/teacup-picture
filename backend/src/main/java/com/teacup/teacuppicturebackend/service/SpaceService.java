package com.teacup.teacuppicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.teacup.teacuppicturebackend.model.dto.space.SpaceAddRequest;
import com.teacup.teacuppicturebackend.model.dto.space.SpaceQueryRequest;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.model.vo.SpaceVO;

import javax.servlet.http.HttpServletRequest;

/**
 * <p>
 * 空间 服务类
 * </p>
 *
 * @author wolves
 * @since 2025-09-27
 */
public interface SpaceService extends IService<Space> {

    /**
     * 空间额度收支入口，约束为「已用 + 在途预留 + 本次 &lt;= 上限」。
     * <p>
     * 四个方法的完整语义与并发理由见 {@code SpaceMapper} 上对应的默认实现，
     * 这里只做转发，不允许在别处再写第二份额度规则。
     */
    boolean tryConsume(long spaceId, long size, long count);

    /** 占用在途预留额度（创建上传会话时）。 */
    boolean reserve(long spaceId, long size);

    /** 把在途预留结转为已用（上传完成时），失败时会释放预留。 */
    boolean settle(long spaceId, long reserved, long actual, long count);

    /** 释放在途预留（取消上传、会话过期清理）。 */
    void releaseReservation(long spaceId, long size);

    /** 扣减已用额度（删除图片等纯释放场景），保底不为负。 */
    void releaseUsage(long spaceId, long size, long count);

    void validSpace(Space space, boolean add);

    void fillSpaceBySpaceLevel(Space space);

    long addSpace(SpaceAddRequest spaceAddRequest, User loginUser);

    SpaceVO getSpaceVO(Space space, HttpServletRequest request);

    Wrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest);

    Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request);

    void checkSpaceAuth(User loginUser, Space oldSpace);
}