package com.teacup.teacuppicturebackend;


import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
public class FutureTest {

    @Test
    public void testFuture() throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Integer> future = executor.submit(() -> {
            // 模拟耗时计算
            Thread.sleep(2000);
            return 42;
        });

        // 主线程可以继续做其他事情...
        System.out.println("任务已提交，继续执行其他操作");

        // 需要结果时再获取（会阻塞直到任务完成）
        Integer result = future.get(); // 阻塞等待
        System.out.println("计算结果: " + result);

    }





}
