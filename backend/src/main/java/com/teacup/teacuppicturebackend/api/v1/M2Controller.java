package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.ai.AiTaskService;
import com.teacup.teacuppicturebackend.api.v1.model.M2Dtos;
import com.teacup.teacuppicturebackend.model.entity.User;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class M2Controller {
    private final M1Service auth;
    private final AiTaskService tasks;

    public M2Controller(M1Service auth, AiTaskService tasks) {
        this.auth = auth;
        this.tasks = tasks;
    }

    @GetMapping("/ai/models")
    public ResponseEntity<V1Response<List<M2Dtos.AiModelView>>> models(HttpServletRequest request) {
        auth.requireUser(request);
        return response(HttpStatus.OK, tasks.models(), request);
    }

    @GetMapping("/ai/quotas/me")
    public ResponseEntity<V1Response<M2Dtos.AiQuotaSummary>> quotas(HttpServletRequest request) {
        return response(HttpStatus.OK, tasks.quotas(auth.requireUser(request)), request);
    }

    @PostMapping("/ai/tasks")
    public ResponseEntity<V1Response<M2Dtos.AiTaskView>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody M2Dtos.CreateAiTaskRequest body, HttpServletRequest request) {
        User user = auth.requireUser(request);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw V1Exception.badRequest("Idempotency-Key 不能为空");
        }
        AiTaskService.CreateResult result = tasks.create(user, body, idempotencyKey);
        return response(result.created() ? HttpStatus.CREATED : HttpStatus.OK, result.task(), request);
    }

    @GetMapping("/ai/tasks")
    public ResponseEntity<V1Response<M2Dtos.AiTaskPage>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        return response(HttpStatus.OK, tasks.list(auth.requireUser(request), page, pageSize, status), request);
    }

    @GetMapping("/ai/tasks/{taskId}")
    public ResponseEntity<V1Response<M2Dtos.AiTaskView>> get(@PathVariable String taskId,
                                                              HttpServletRequest request) {
        return response(HttpStatus.OK, tasks.get(auth.requireUser(request), parseId(taskId)), request);
    }

    @PostMapping("/ai/tasks/{taskId}/cancel")
    public ResponseEntity<V1Response<M2Dtos.AiTaskView>> cancel(@PathVariable String taskId,
                                                                 HttpServletRequest request) {
        return response(HttpStatus.OK, tasks.cancel(auth.requireUser(request), parseId(taskId)), request);
    }

    @GetMapping("/ai/tasks/{taskId}/download")
    public ResponseEntity<Resource> download(@PathVariable String taskId, HttpServletRequest request) {
        AiTaskService.Download value = tasks.download(auth.requireUser(request), parseId(taskId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(value.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(value.mediaType()).body(value.resource());
    }

    @PatchMapping("/admin/ai/models/{modelId}")
    public ResponseEntity<V1Response<M2Dtos.AiModelView>> updateModel(
            @PathVariable String modelId, @RequestBody M2Dtos.UpdateAiModelRequest body,
            HttpServletRequest request) {
        return response(HttpStatus.OK,
                tasks.updateModel(auth.requireUser(request), parseId(modelId), body), request);
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw V1Exception.badRequest("ID 格式无效");
        }
    }

    private static <T> ResponseEntity<V1Response<T>> response(HttpStatus status, T data,
                                                               HttpServletRequest request) {
        return ResponseEntity.status(status).body(V1Response.success(data, RequestIdFilter.get(request)));
    }
}
