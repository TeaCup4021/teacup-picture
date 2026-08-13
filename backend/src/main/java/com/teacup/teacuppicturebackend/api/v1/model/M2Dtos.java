package com.teacup.teacuppicturebackend.api.v1.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class M2Dtos {
    private M2Dtos() {
    }

    public record AiModelView(String id, String code, String name, List<String> capabilities,
                              List<String> ratios, List<String> qualities, boolean supportsReference,
                              int quotaCost, boolean enabled) {}
    public record AiQuotaView(String taskType, int dailyLimit, int used, int remaining) {}
    public record AiQuotaSummary(LocalDate date, List<AiQuotaView> quotas) {}
    public record CreateAiTaskRequest(String type, String modelCode, String prompt, String ratio,
                                      String quality, String sourcePictureId, String referencePictureId) {}
    public record AiPictureRef(String id, String name, String thumbnailUrl, String url) {}
    public record AiTaskView(String id, String type, AiModelView model, String prompt, String ratio,
                             String quality, String status, AiPictureRef sourcePicture,
                             AiPictureRef referencePicture, AiPictureRef resultPicture,
                             String failureCode, String failureReason, boolean quotaRefunded,
                             Instant createdAt, Instant startedAt, Instant finishedAt,
                             String downloadUrl) {}
    public record AiTaskPage(List<AiTaskView> items, M1Dtos.PageMeta page) {}
    public record UpdateAiModelRequest(String name, List<String> capabilities, List<String> ratios,
                                       List<String> qualities, Boolean supportsReference,
                                       Integer quotaCost, Boolean enabled) {}
}
