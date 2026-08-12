package com.teacup.teacuppicturebackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
   public class ThreadPoolConfig {

       @Bean
       public ThreadPoolExecutor threadPoolExecutor() {
           return new ThreadPoolExecutor(
               5,  // corePoolSize
               10, // maximumPoolSize
               60L, TimeUnit.SECONDS,
               new LinkedBlockingQueue<Runnable>()
           );
       }
   }
   