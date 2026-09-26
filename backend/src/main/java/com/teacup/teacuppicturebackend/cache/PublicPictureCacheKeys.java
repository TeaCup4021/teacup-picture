package com.teacup.teacuppicturebackend.cache;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 公开读模型的缓存键定义，集中在此处，避免读写两侧各拼一遍导致不一致。
 *
 * 三类键的规则刻意不同：
 *  本地键一律带版本号——本地缓存无法被其它实例精确清理，靠换号实现整体逻辑失效；
 *  详情共享键不带版本号——键数量有界且与对象一一对应，可被任意实例精确删除，
 *      同时省去读取时取版本号的一次网络往返；
 *  列表共享键带版本号——游标与每页条数的组合没有上界，无法枚举删除，只能整体切换。
 */
public final class PublicPictureCacheKeys {

    /** 公开图片详情的缓存名。 */
    public static final String DETAIL_CACHE_NAME = "public-picture-detail";

    /** 公开图库列表的缓存名。 */
    public static final String LIST_CACHE_NAME = "public-picture-list";

    /** 共享缓存统一前缀。 */
    public static final String SHARED_PREFIX = "tp:cache:";

    /** 目录版本号在共享缓存中的计数键。 */
    public static final String CATALOG_GENERATION_KEY = "tp:cache:gen:catalog";

    private PublicPictureCacheKeys() {
    }

    /** 本地缓存键：版本号 + 缓存名 + 标识。 */
    public static String localKey(long catalog, String cacheName, String identity) {
        return catalog + ":" + cacheName + ":" + identity;
    }

    /** 详情共享键：不含版本号，失效靠精确删除。 */
    public static String detailSharedKey(long pictureId) {
        return SHARED_PREFIX + DETAIL_CACHE_NAME + ":id=" + pictureId;
    }

    /** 列表共享键：含版本号，失效靠版本号整体切换。 */
    public static String listSharedKey(long catalog, String identity) {
        return SHARED_PREFIX + LIST_CACHE_NAME + ":g" + catalog + ":" + identity;
    }

    /** 规范化游标：先编码再进键，避免把用户输入原样拼进键名。 */
    public static String canonicalCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return "first";
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
    }

    /** 列表标识：只允许调用方传入已校验的每页条数，限制键的基数。 */
    public static String listIdentity(String cursor, int limit) {
        return "cursor=" + canonicalCursor(cursor) + ":limit=" + limit;
    }
}
