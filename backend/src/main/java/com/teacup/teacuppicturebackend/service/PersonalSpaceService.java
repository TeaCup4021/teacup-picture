package com.teacup.teacuppicturebackend.service;

import com.teacup.teacuppicturebackend.model.entity.Space;

public interface PersonalSpaceService {

    Space getOrCreatePersonalSpace(Long userId);
}
