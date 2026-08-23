package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.api.v1.model.M3Dtos;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1")
public class M3Controller {
    private final M1Service auth;
    private final M3Service service;

    @javax.annotation.Resource
    private CollaborationService collaboration;

    @javax.annotation.Resource
    private ObjectMapper objectMapper;

    public M3Controller(M1Service auth, M3Service service) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping("/pictures/{pictureId}/editor-state")
    public ResponseEntity<V1Response<M3Dtos.EditorStateView>> getDraft(
            @PathVariable String pictureId, HttpServletRequest request) {
        return response(HttpStatus.OK,
                service.getDraft(auth.requireUser(request), parseId(pictureId)), request);
    }

    @PutMapping("/pictures/{pictureId}/editor-state")
    public ResponseEntity<V1Response<M3Dtos.EditorStateView>> saveDraft(
            @PathVariable String pictureId, @RequestBody M3Dtos.SaveDraftRequest body,
            HttpServletRequest request) {
        return response(HttpStatus.OK,
                service.saveDraft(auth.requireUser(request), parseId(pictureId),
                        body == null ? null : body.editorState(),
                        body == null ? null : body.expectedRevision()), request);
    }

    @DeleteMapping("/pictures/{pictureId}/editor-state")
    public ResponseEntity<V1Response<M3Dtos.EditorStateView>> deleteDraft(
            @PathVariable String pictureId,
            @RequestBody(required = false) M3Dtos.DeleteDraftRequest body,
            HttpServletRequest request) {
        return response(HttpStatus.OK,
                service.deleteDraft(auth.requireUser(request), parseId(pictureId),
                        body == null ? null : body.expectedRevision()), request);
    }

    @GetMapping("/pictures/{pictureId}/versions")
    public ResponseEntity<V1Response<M3Dtos.VersionList>> listVersions(
            @PathVariable String pictureId, HttpServletRequest request) {
        return response(HttpStatus.OK,
                service.listVersions(auth.requireUser(request), parseId(pictureId)), request);
    }

    @GetMapping("/pictures/{pictureId}/versions/{versionId}")
    public ResponseEntity<V1Response<M3Dtos.VersionDetail>> getVersion(
            @PathVariable String pictureId, @PathVariable String versionId,
            HttpServletRequest request) {
        return response(HttpStatus.OK,
                service.getVersion(auth.requireUser(request), parseId(pictureId), parseId(versionId)), request);
    }

    @PostMapping(value = "/pictures/{pictureId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<V1Response<M3Dtos.VersionDetail>> createVersion(
            @PathVariable String pictureId,
            @RequestPart("file") MultipartFile preview,
            @RequestParam String editorState,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String note,
            @RequestParam(required = false, defaultValue = "user_save") String sourceType,
            HttpServletRequest request) {
        User user = auth.requireUser(request);
        return response(HttpStatus.CREATED,
                service.createVersion(user, parseId(pictureId), preview, editorState, name, note, sourceType),
                request);
    }

    @PostMapping(value = "/pictures/{pictureId}/editor-saves", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<V1Response<M3Dtos.EditorSaveResult>> saveEditorResult(
            @PathVariable String pictureId,
            @RequestPart("file") MultipartFile image,
            @RequestParam String mode,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Long expectedRevision,
            HttpServletRequest request) {
        User user = auth.requireUser(request);
        M3Dtos.EditorSaveResult result = service.saveEditorResult(user, parseId(pictureId), image,
                mode, name, expectedRevision);
        if (collaboration != null && "replace".equals(result.mode())) {
            String state = request.getParameter("collaborationEditorState");
            collaboration.rotateRoomEpoch(user, parseId(pictureId), parseJson(state));
        }
        return response(HttpStatus.OK, result, request);
    }

    @PostMapping("/pictures/{pictureId}/versions/{versionId}/restore")
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<V1Response<M3Dtos.VersionDetail>> restoreVersion(
            @PathVariable String pictureId, @PathVariable String versionId,
            @RequestBody(required = false) M3Dtos.RestoreVersionRequest body,
            HttpServletRequest request) {
        User user = auth.requireUser(request);
        M3Dtos.VersionDetail result = service.restoreVersion(user, parseId(pictureId), parseId(versionId),
                body == null ? null : body.expectedRevision());
        if (collaboration != null) collaboration.rotateRoomEpoch(user, parseId(pictureId), result.editorState());
        return response(HttpStatus.CREATED, result, request);
    }

    @GetMapping("/pictures/{pictureId}/versions/{versionId}/content")
    public ResponseEntity<Resource> versionContent(
            @PathVariable String pictureId, @PathVariable String versionId,
            @RequestParam(defaultValue = "original") String variant,
            HttpServletRequest request) {
        PictureStorage.StoredObject object = service.loadVersionContent(
                auth.requireUser(request), parseId(pictureId), parseId(versionId), variant);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(object.size())
                .contentType(MediaType.parseMediaType(object.contentType()))
                .body(object.resource());
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

    private Object parseJson(String value) {
        if (value == null || value.isBlank()) return null;
        try { return objectMapper.readTree(value); } catch (Exception exception) { throw V1Exception.badRequest("协作编辑状态格式无效"); }
    }

    private static <T> ResponseEntity<V1Response<T>> response(HttpStatus status, T data,
                                                               HttpServletRequest request) {
        return ResponseEntity.status(status).body(V1Response.success(data, RequestIdFilter.get(request)));
    }
}
