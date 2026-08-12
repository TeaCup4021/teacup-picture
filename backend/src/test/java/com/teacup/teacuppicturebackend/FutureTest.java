package com.teacup.teacuppicturebackend;


import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FutureTest {

    @Test
    public void testFuture() throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> future = executor.submit(() -> 42);
            assertEquals(42, future.get());
        } finally {
            executor.shutdownNow();
        }

    }





}
