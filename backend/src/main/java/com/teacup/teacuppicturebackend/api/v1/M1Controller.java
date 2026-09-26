package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.auth.SessionContext;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.storage.PictureAssetService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.List;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1")
public class M1Controller {
    private final M1Service service;
    private final PictureAssetService assets;

    public M1Controller(M1Service service, PictureAssetService assets) {
        this.service = service;
        this.assets = assets;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<V1Response<M1Dtos.RegistrationResult>> register(@RequestBody M1Dtos.RegisterRequest body,
                                                                          HttpServletRequest request) {
        return response(HttpStatus.CREATED, service.register(body), request);
    }

    @PostMapping("/auth/login")
    public ResponseEntity<V1Response<M1Dtos.CurrentUser>> login(@RequestBody M1Dtos.LoginRequest body,
                                                                HttpServletRequest request) {
        return response(HttpStatus.OK, service.currentUser(service.login(body, request)), request);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<V1Response<Boolean>> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        boolean expireCookie = session == null;
        if (session != null) {
            SessionContext.removeLoginState(session, request);
            if (!SessionContext.hasAnyLoginState(session)) {
                session.invalidate();
                expireCookie = true;
            }
        }
        if (expireCookie) {
            response.addHeader("Set-Cookie", "TEACUP_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        }
        return response(HttpStatus.OK, true, request);
    }

    @GetMapping("/auth/me")
    public ResponseEntity<V1Response<M1Dtos.CurrentUser>> me(HttpServletRequest request) {
        return response(HttpStatus.OK, service.currentUser(service.requireUser(request)), request);
    }

    @GetMapping("/spaces/personal")
    public ResponseEntity<V1Response<M1Dtos.PersonalSpace>> personalSpace(HttpServletRequest request) {
        return response(HttpStatus.OK, service.personalSpace(service.requireUser(request)), request);
    }

    @PostMapping(value = "/pictures/uploads", consumes = "multipart/form-data")
    public ResponseEntity<V1Response<M1Dtos.PictureDetail>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String introduction,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<String> tags,
            HttpServletRequest request) {
        User user = service.requireUser(request);
        return response(HttpStatus.CREATED, service.upload(user, file, spaceId, name, introduction, category, tags), request);
    }

    @PostMapping("/picture-upload-sessions")
    public ResponseEntity<V1Response<M1Dtos.UploadSessionView>> createUploadSession(
            @RequestBody M1Dtos.UploadSessionCreateRequest body, HttpServletRequest request) {
        return response(HttpStatus.CREATED, service.createUploadSession(service.requireUser(request), body), request);
    }

    @GetMapping("/picture-upload-sessions/{sessionId}")
    public ResponseEntity<V1Response<M1Dtos.UploadSessionView>> getUploadSession(
            @PathVariable String sessionId, HttpServletRequest request) {
        return response(HttpStatus.OK, service.getUploadSession(service.requireUser(request), parseId(sessionId)), request);
    }

    @PutMapping(value = "/picture-upload-sessions/{sessionId}/parts/{partNumber}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<V1Response<M1Dtos.UploadSessionView>> uploadPart(
            @PathVariable String sessionId, @PathVariable int partNumber,
            HttpServletRequest request, @RequestHeader(value = "X-Chunk-SHA256", required = false) String checksum) throws java.io.IOException {
        long size = request.getContentLengthLong();
        if (size < 0) throw V1Exception.badRequest("缺少分片大小");
        return response(HttpStatus.OK, service.uploadPart(service.requireUser(request), parseId(sessionId), partNumber,
                request.getInputStream(), size, checksum), request);
    }

    @PostMapping("/picture-upload-sessions/{sessionId}/complete")
    public ResponseEntity<V1Response<M1Dtos.PictureDetail>> completeUploadSession(
            @PathVariable String sessionId, @RequestBody(required = false) M1Dtos.UploadSessionCreateRequest body,
            HttpServletRequest request) {
        return response(HttpStatus.CREATED, service.completeUploadSession(service.requireUser(request), parseId(sessionId), body), request);
    }

    @DeleteMapping("/picture-upload-sessions/{sessionId}")
    public ResponseEntity<V1Response<Boolean>> abortUploadSession(@PathVariable String sessionId, HttpServletRequest request) {
        service.abortUploadSession(service.requireUser(request), parseId(sessionId));
        return response(HttpStatus.OK, true, request);
    }

    @PostMapping("/pictures/url-imports")
    public ResponseEntity<V1Response<M1Dtos.PictureDetail>> importUrl(@RequestBody M1Dtos.UrlImportRequest body,
                                                                      HttpServletRequest request) {
        User user = service.requireUser(request);
        return response(HttpStatus.CREATED, service.importUrl(user, body), request);
    }

    @PostMapping(value = "/pictures/url-imports", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<V1Response<M1Dtos.PictureDetail>> importUrlForm(
            @RequestParam String url,
            @RequestParam(required = false) String previewToken,
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String introduction,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<String> tags,
            HttpServletRequest request) {
        User user = service.requireUser(request);
        M1Dtos.UrlImportRequest body = new M1Dtos.UrlImportRequest(url, previewToken, spaceId, name, introduction, category, tags);
        return response(HttpStatus.CREATED, service.importUrl(user, body), request);
    }

    @GetMapping("/pictures/url-preview")
    public ResponseEntity<Resource> previewUrl(@RequestParam String url, HttpServletRequest request) {
        com.teacup.teacuppicturebackend.storage.UrlImportPreviewService.Preview object = service.previewUrl(service.requireUser(request), url);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Teacup-Preview-Token", object.token())
                .contentLength(object.size())
                .contentType(org.springframework.http.MediaType.parseMediaType(object.contentType()))
                .body(object.resource());
    }

    @GetMapping("/pictures")
    public ResponseEntity<V1Response<M1Dtos.PicturePage>> pictures(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String spaceId,
            HttpServletRequest request) {
        return response(HttpStatus.OK, service.listPictures(service.requireUser(request), page, pageSize, spaceId), request);
    }

    @GetMapping("/pictures/{pictureId}")
    public ResponseEntity<V1Response<M1Dtos.PictureDetail>> picture(@PathVariable String pictureId,
                                                                    HttpServletRequest request) {
        return response(HttpStatus.OK, service.getPicture(service.requireUser(request), parseId(pictureId)), request);
    }

    @PostMapping("/pictures/{pictureId}/publish-requests")
    public ResponseEntity<V1Response<M1Dtos.PublishRequestView>> requestPublish(@PathVariable String pictureId,
                                                                                HttpServletRequest request) {
        return response(HttpStatus.CREATED, service.requestPublication(service.requireUser(request), parseId(pictureId)), request);
    }

    @GetMapping("/admin/publish-requests")
    public ResponseEntity<V1Response<M1Dtos.PublishRequestPage>> publishRequests(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String pictureId,
            HttpServletRequest request) {
        Long parsedPictureId = pictureId == null ? null : parseId(pictureId);
        return response(HttpStatus.OK, service.listPublishRequests(service.requireUser(request), page, pageSize, status, parsedPictureId), request);
    }

    @GetMapping("/admin/publish-requests/{requestId}")
    public ResponseEntity<V1Response<M1Dtos.PublishRequestView>> publishRequest(@PathVariable String requestId,
                                                                                HttpServletRequest request) {
        return response(HttpStatus.OK, service.getPublishRequest(service.requireUser(request), parseId(requestId)), request);
    }

    @PostMapping("/admin/publish-requests/{requestId}/approve")
    public ResponseEntity<V1Response<M1Dtos.PublishRequestView>> approve(
            @PathVariable String requestId, @RequestBody(required = false) M1Dtos.DecisionRequest body,
            HttpServletRequest request) {
        String note = body == null ? null : body.note();
        return response(HttpStatus.OK, service.decide(service.requireUser(request), parseId(requestId), true, note), request);
    }

    @PostMapping("/admin/publish-requests/{requestId}/reject")
    public ResponseEntity<V1Response<M1Dtos.PublishRequestView>> reject(
            @PathVariable String requestId, @RequestBody M1Dtos.DecisionRequest body,
            HttpServletRequest request) {
        return response(HttpStatus.OK, service.decide(service.requireUser(request), parseId(requestId), false,
                body == null ? null : body.reason()), request);
    }

    @PostMapping("/admin/pictures/{pictureId}/withdraw")
    public ResponseEntity<V1Response<M1Dtos.PictureDetail>> withdraw(
            @PathVariable String pictureId, @RequestBody M1Dtos.DecisionRequest body,
            HttpServletRequest request) {
        return response(HttpStatus.OK, service.withdraw(service.requireUser(request), parseId(pictureId),
                body == null ? null : body.reason()), request);
    }

    @GetMapping("/public/pictures")
    public ResponseEntity<V1Response<M1Dtos.PublicPictureCursorPage>> publicPictures(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request) {
        return publicResponse(service.publicPictures(cursor, limit), request, ifNoneMatch, Duration.ofSeconds(10));
    }

    @GetMapping("/public/pictures/{pictureId}")
    public ResponseEntity<V1Response<M1Dtos.PublicPictureDetail>> publicPicture(@PathVariable String pictureId,
                                                                                @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                                                                HttpServletRequest request) {
        return publicResponse(service.publicPicture(parseId(pictureId)), request, ifNoneMatch, Duration.ofSeconds(30));
    }

    @GetMapping("/pictures/{pictureId}/content")
    public ResponseEntity<Resource> privateContent(@PathVariable String pictureId,
                                                    @RequestParam(defaultValue = "original") String variant,
                                                    HttpServletRequest request) {
        PictureStorage.StoredObject object = assets.loadPrivate(service.requireUser(request), parseId(pictureId), variant);
        return assetResponse(object, true);
    }

    @GetMapping("/public/pictures/{pictureId}/content")
    public ResponseEntity<Resource> publicContent(@PathVariable String pictureId,
                                                   @RequestParam(defaultValue = "original") String variant,
                                                   @RequestParam(required = false) String version,
                                                   @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        long parsedPictureId = parseId(pictureId);
        Long parsedVersion = version == null || version.isBlank() ? null : parseId(version);
        PictureStorage.StoredObject object = assets.loadPublic(parsedPictureId, parsedVersion, variant);
        String etag = publicAssetEtag(parsedPictureId, parsedVersion, variant, object.size());
        return assetResponse(object, false, etag, ifNoneMatch);
    }

    private static ResponseEntity<Resource> assetResponse(PictureStorage.StoredObject object, boolean privateAsset) {
        return assetResponse(object, privateAsset, null, null);
    }

    private static ResponseEntity<Resource> assetResponse(PictureStorage.StoredObject object, boolean privateAsset,
                                                           String etag, String ifNoneMatch) {
        CacheControl cache = privateAsset ? CacheControl.noStore()
                : CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic().mustRevalidate();
        if (!privateAsset && etagMatches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(cache).eTag(etag).build();
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(cache);
        if (etag != null) response.eTag(etag);
        return response
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(object.size())
                .contentType(org.springframework.http.MediaType.parseMediaType(object.contentType()))
                .body(object.resource());
    }

    private static <T> ResponseEntity<V1Response<T>> publicResponse(T data, HttpServletRequest request,
                                                                      String ifNoneMatch, Duration maxAge) {
        String etag = weakEtag(data);
        CacheControl cache = CacheControl.maxAge(maxAge).cachePublic().mustRevalidate();
        if (etagMatches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(cache).eTag(etag).build();
        }
        return ResponseEntity.ok().cacheControl(cache).eTag(etag)
                .body(V1Response.success(data, RequestIdFilter.get(request)));
    }

    private static String publicAssetEtag(long pictureId, Long versionId, String variant, long size) {
        return "W/\"p-" + pictureId + "-v-" + (versionId == null ? "current" : versionId)
                + "-" + variant + "-" + size + "\"";
    }

    private static String weakEtag(Object value) {
        return "W/\"" + Integer.toUnsignedString(value.hashCode(), 16) + "\"";
    }

    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || etag == null) return false;
        for (String candidate : ifNoneMatch.split(",")) {
            String normalized = candidate.trim();
            if ("*".equals(normalized) || etag.equals(normalized)) return true;
        }
        return false;
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

    private static <T> ResponseEntity<V1Response<T>> response(HttpStatus status, T data, HttpServletRequest request) {
        return ResponseEntity.status(status).body(V1Response.success(data, RequestIdFilter.get(request)));
    }
}
