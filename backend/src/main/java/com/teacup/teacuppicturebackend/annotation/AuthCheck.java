package com.teacup.teacuppicturebackend.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

//指定该注解只能用于方法上
@Target(ElementType.METHOD)
//指定该注解在运行时保留，可通过反射获取
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthCheck {

    //必须有某个角色
    String mustRole() default "";
}


