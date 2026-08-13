package com.teacup.teacuppicturebackend;

import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.service.PictureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest
@Tag("integration")
public class PictureMockDataTest {

    @Resource
    private PictureService pictureService;

    @Test
    public void mockPictureData() {
        int batchSize = 1000;
        int totalRows = 5000;
        
        List<Picture> pictureList = new ArrayList<>(batchSize);
        long userId = 1967927204612521994L; // 假设有一个ID为1的用户，如果没有可以修改为一个存在的用户ID
        
        for (int i = 1; i <= totalRows; i++) {
            Picture picture = new Picture();
            picture.setUrl("https://dummyimage.com/600x400/000/fff&text=mock_" + i);
            picture.setName("Mock图片_" + i);
            picture.setIntroduction("这是一张用于测试压力性能的Mock图片，编号：" + i);
            picture.setCategory((i % 2 == 0) ? "唯美" : "科技");
            picture.setTags("[\"测试\", \"压测\"]");
            picture.setPicSize(102400L);
            picture.setPicWidth(600);
            picture.setPicHeight(400);
            picture.setPicScale(1.5);
            picture.setPicFormat("jpg");
            picture.setUserId(userId);
            picture.setReviewStatus(1); // 1表示审核通过，这样在前端和接口中才能正常查出来
            picture.setReviewMessage("自动通过");
            
            pictureList.add(picture);

            if (pictureList.size() >= batchSize) {
                pictureService.saveBatch(pictureList);
                pictureList.clear();
                System.out.println("已成功插入 " + i + " 条数据");
            }
        }
        
        if (!pictureList.isEmpty()) {
            pictureService.saveBatch(pictureList);
            System.out.println("数据全部插入完成！");
        }
    }
}
