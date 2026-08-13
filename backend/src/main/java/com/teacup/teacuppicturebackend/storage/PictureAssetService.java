package com.teacup.teacuppicturebackend.storage;

import com.teacup.teacuppicturebackend.api.v1.V1Exception;
import com.teacup.teacuppicturebackend.config.PictureStorageConfig;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.UserService;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service
public class PictureAssetService {
    private final PictureMapper pictureMapper;
    private final UserService userService;
    private final PictureStorage storage;
    private final String baseUrl;

    public PictureAssetService(PictureMapper pictureMapper, UserService userService, PictureStorage storage,
                               PictureStorageConfig config) {
        this.pictureMapper = pictureMapper;
        this.userService = userService;
        this.storage = storage;
        this.baseUrl = config.getPublicBaseUrl().replaceAll("/+$", "");
    }

    public String privateUrl(long pictureId, String variant) {
        return baseUrl + "/pictures/" + pictureId + "/content?variant=" + normalizedVariant(variant);
    }

    public String publicUrl(long pictureId, String variant) {
        return baseUrl + "/public/pictures/" + pictureId + "/content?variant=" + normalizedVariant(variant);
    }

    public PictureStorage.StoredObject loadPrivate(User user, long pictureId, String variant) {
        Picture picture = requirePicture(pictureId);
        if (!Objects.equals(picture.getUserId(), user.getId()) && !userService.isAdmin(user)) {
            throw V1Exception.notFound();
        }
        return load(picture, variant);
    }

    public PictureStorage.StoredObject loadPublic(long pictureId, String variant) {
        Picture picture = requirePicture(pictureId);
        if (!"public".equals(picture.getVisibility()) || !"approved".equals(picture.getPublishStatus())) {
            throw V1Exception.notFound();
        }
        return load(picture, variant);
    }

    private PictureStorage.StoredObject load(Picture picture, String variant) {
        String normalized = normalizedVariant(variant);
        String key = "thumbnail".equals(normalized) && picture.getThumbnailObjectKey() != null
                ? picture.getThumbnailObjectKey() : picture.getObjectKey();
        if (key == null || key.isBlank()) throw V1Exception.notFound();
        return storage.load(key);
    }

    private Picture requirePicture(long pictureId) {
        Picture picture = pictureMapper.selectById(pictureId);
        if (picture == null || Integer.valueOf(1).equals(picture.getIsDelete())) throw V1Exception.notFound();
        return picture;
    }

    private static String normalizedVariant(String variant) {
        if (variant == null || variant.isBlank() || "original".equals(variant)) return "original";
        if ("thumbnail".equals(variant)) return "thumbnail";
        throw V1Exception.badRequest("图片规格无效");
    }

}
