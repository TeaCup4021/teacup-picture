package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.api.v1.model.CollaborationDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/pictures/{pictureId}/collaboration")
public class CollaborationController {
    private final M1Service auth;
    private final CollaborationService collaboration;

    public CollaborationController(M1Service auth, CollaborationService collaboration) {
        this.auth = auth;
        this.collaboration = collaboration;
    }

    @GetMapping("/session")
    public ResponseEntity<V1Response<CollaborationDtos.Session>> session(
            @PathVariable String pictureId, HttpServletRequest request) {
        return response(HttpStatus.OK, collaboration.getSession(auth.requireUser(request), parseId(pictureId)), request);
    }

    @PostMapping("/checkpoint")
    public ResponseEntity<V1Response<CollaborationDtos.CheckpointResult>> checkpoint(
            @PathVariable String pictureId, @RequestBody CollaborationDtos.CheckpointRequest body,
            HttpServletRequest request) {
        return response(HttpStatus.OK, collaboration.checkpoint(auth.requireUser(request), parseId(pictureId), body), request);
    }

    private static long parseId(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException exception) { throw V1Exception.badRequest("ID 格式错误"); }
    }

    private static <T> ResponseEntity<V1Response<T>> response(HttpStatus status, T data, HttpServletRequest request) {
        return ResponseEntity.status(status).body(V1Response.success(data, RequestIdFilter.get(request)));
    }
}
