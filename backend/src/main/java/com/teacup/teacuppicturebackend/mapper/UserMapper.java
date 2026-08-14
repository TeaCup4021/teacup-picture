package com.teacup.teacuppicturebackend.mapper;

import com.teacup.teacuppicturebackend.model.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
* @author wolves
* @description 针对表【user(用户)】的数据库操作Mapper
* @createDate 2025-09-14 21:20:40
* @Entity com.teacup.teacuppicturebackend.model.entity.User
*/
public interface UserMapper extends BaseMapper<User> {
    @Select("SELECT id FROM user WHERE id = #{userId} FOR UPDATE")
    Long lockById(@Param("userId") long userId);
}




