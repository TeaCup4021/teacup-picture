package com.teacup.teacuppicturebackend.auth;

import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.entity.SpaceUser;
import lombok.Data;

@Data
 public class SpaceUserAuthContext {
    private Long id;
    private Long pictureId;
    private Long spaceId;
    private Long spaceUserId;
    private Picture picture;
    private Space space;
    private SpaceUser spaceUser;
 }