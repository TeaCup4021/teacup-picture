package com.teacup.teacuppicturebackend.manager.websocket.model;

import com.teacup.teacuppicturebackend.model.vo.UserVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片编辑响应信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PictureEditResponseMessage {

    
    private String type;

    
    private String message;

    
    private String editAction;

    
    private UserVO user;
}