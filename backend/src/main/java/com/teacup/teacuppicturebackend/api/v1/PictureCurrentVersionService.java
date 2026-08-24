package com.teacup.teacuppicturebackend.api.v1;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.PictureVersionMapper;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.PictureVersion;
import org.springframework.stereotype.Service;

@Service
public class PictureCurrentVersionService {
    private final PictureMapper pictures;
    private final PictureVersionMapper versions;
    private final ObjectMapper json;

    public PictureCurrentVersionService(PictureMapper pictures, PictureVersionMapper versions, ObjectMapper json) {
        this.pictures = pictures;
        this.versions = versions;
        this.json = json;
    }

    public PictureVersion createInitial(Picture picture, long creatorId, String sourceType) {
        if (picture == null || picture.getId() == null || picture.getObjectKey() == null) {
            throw new IllegalArgumentException("图片版本基线参数不完整");
        }
        PictureVersion version = new PictureVersion();
        version.setPictureId(picture.getId());
        version.setVersionNumber(versions.selectMaxVersionNumber(picture.getId()) + 1);
        version.setName("原始图片");
        version.setNote("图片创建时自动保存");
        version.setSourceType(sourceType == null ? "original" : sourceType);
        version.setEditorState(emptyState(width(picture), height(picture)));
        version.setSchemaVersion(3);
        version.setAssetObjectKey(picture.getObjectKey());
        version.setThumbnailObjectKey(picture.getThumbnailObjectKey());
        version.setContentType(picture.getContentType());
        version.setWidth(width(picture));
        version.setHeight(height(picture));
        version.setSize(picture.getPicSize() == null ? 0 : picture.getPicSize());
        version.setCreatorId(creatorId);
        versions.insert(version);
        picture.setCurrentVersionId(version.getId());
        pictures.updateById(picture);
        return version;
    }

    public PictureVersion requireCurrent(Picture picture) {
        if (picture == null || picture.getId() == null) throw V1Exception.notFound();
        if (picture.getCurrentVersionId() != null) {
            PictureVersion current = versions.selectById(picture.getCurrentVersionId());
            if (current != null && picture.getId().equals(current.getPictureId())) return current;
        }
        PictureVersion matching = versions.selectOne(new LambdaQueryWrapper<PictureVersion>()
                .eq(PictureVersion::getPictureId, picture.getId())
                .eq(PictureVersion::getAssetObjectKey, picture.getObjectKey())
                .orderByDesc(PictureVersion::getId).last("LIMIT 1"));
        if (matching == null) matching = createInitial(picture, picture.getUserId(), "original");
        else {
            picture.setCurrentVersionId(matching.getId());
            pictures.updateById(picture);
        }
        return matching;
    }

    private String emptyState(int width, int height) {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", 3);
        root.putObject("canvas").put("width", width).put("height", height);
        root.putObject("transform").put("rotation", 0).put("scale", 1).put("flipX", false).put("flipY", false);
        root.putNull("crop");
        ObjectNode adjustments = root.putObject("adjustments");
        for (String field : new String[]{"exposure", "brightness", "contrast", "highlights", "shadows",
                "saturation", "vibrance", "temperature", "tint", "sharpness", "fade", "vignette", "enhance", "dehaze"}) {
            adjustments.put(field, 0);
        }
        root.putArray("layers");
        return root.toString();
    }

    private static int width(Picture picture) { return picture.getPicWidth() == null || picture.getPicWidth() < 1 ? 1 : picture.getPicWidth(); }
    private static int height(Picture picture) { return picture.getPicHeight() == null || picture.getPicHeight() < 1 ? 1 : picture.getPicHeight(); }
}
