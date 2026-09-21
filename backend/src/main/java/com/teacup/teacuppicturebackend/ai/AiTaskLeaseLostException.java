package com.teacup.teacuppicturebackend.ai;

/**
 * 任务执行租约在结果落库之前已归属他人。
 * 语义：本实例的结果作废，不得调用 fail（任务的归属已不属于本实例）。
 */
public class AiTaskLeaseLostException extends RuntimeException {
    public AiTaskLeaseLostException(String message) {
        super(message);
    }
}
