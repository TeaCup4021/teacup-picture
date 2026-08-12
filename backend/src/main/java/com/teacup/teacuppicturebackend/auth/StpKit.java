package com.teacup.teacuppicturebackend.auth;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

/**
 * 定义空间账号体系
 */
@Component
 public class StpKit {
    public static final String SPACE_TYPE = "space";
    public static final StpLogic DEFAULT = StpUtil.stpLogic;
    public static final StpLogic SPACE = new StpLogic(SPACE_TYPE);
 }