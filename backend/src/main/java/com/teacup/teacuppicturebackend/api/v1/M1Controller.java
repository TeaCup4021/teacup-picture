package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.model.entity.User;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class M1Controller {
    private final M1Service service;
    private final LocalPictureStorage storage;

    public M1Controller(M1Service service, LocalPictureStorage storage) {
        this.service = service;
        this.storage = storage;
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
        if (session != null) session.invalidate();
        response.addHeader("Set-Cookie", "TEACUP_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
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

    @PostMapping("/pictures/url-imports")
    public ResponseEntity<V1Response<M1Dtos.PictureDetail>> importUrl(@RequestBody M1Dtos.UrlImportRequest body,
                                                                      HttpServletRequest request) {
        User user = service.requireUser(request);
        return response(HttpStatus.CREATED, service.importUrl(user, body), request);
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
            HttpServletRequest request) {
        return response(HttpStatus.OK, service.publicPictures(cursor, limit), request);
    }

    @GetMapping("/public/pictures/{pictureId}")
    public ResponseEntity<V1Response<M1Dtos.PublicPictureDetail>> publicPicture(@PathVariable String pictureId,
                                                                               HttpServletRequest request) {
        return response(HttpStatus.OK, service.publicPicture(parseId(pictureId)), request);
    }

    @GetMapping("/public/assets/{fileName:.+}")
    public ResponseEntity<Resource> asset(@PathVariable String fileName) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(storage.mediaType(fileName)).body(storage.load(fileName));
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
