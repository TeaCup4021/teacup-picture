package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.api.v1.model.M6Dtos;
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

@RestController
@RequestMapping("/api/v1")
public class M6Controller {
    private final M1Service auth;
    private final M6Service service;

    public M6Controller(M1Service auth, M6Service service) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping("/pictures/{pictureId}/shares")
    public ResponseEntity<V1Response<M6Dtos.ShareView>> activeShare(@PathVariable String pictureId, HttpServletRequest request) {
        return response(HttpStatus.OK, service.activeShare(auth.requireUser(request), id(pictureId)), request);
    }

    @PostMapping("/pictures/{pictureId}/shares")
    public ResponseEntity<V1Response<M6Dtos.ShareView>> createShare(@PathVariable String pictureId,
            @RequestBody(required = false) M6Dtos.CreateShareRequest body, HttpServletRequest request) {
        return response(HttpStatus.CREATED, service.createShare(auth.requireUser(request), id(pictureId), body, false), request);
    }

    @PostMapping("/pictures/{pictureId}/shares/regenerate")
    public ResponseEntity<V1Response<M6Dtos.ShareView>> regenerateShare(@PathVariable String pictureId,
            @RequestBody(required = false) M6Dtos.CreateShareRequest body, HttpServletRequest request) {
        return response(HttpStatus.CREATED, service.createShare(auth.requireUser(request), id(pictureId), body, true), request);
    }

    @DeleteMapping("/pictures/{pictureId}/shares/{shareId}")
    public ResponseEntity<V1Response<Void>> revokeShare(@PathVariable String pictureId, @PathVariable String shareId,
                                                        HttpServletRequest request) {
        service.revokeShare(auth.requireUser(request), id(pictureId), id(shareId));
        return response(HttpStatus.OK, null, request);
    }

    @PostMapping("/public/shares/{publicId}/access")
    public ResponseEntity<V1Response<M6Dtos.ShareAccessResult>> accessShare(@PathVariable String publicId,
            @RequestBody M6Dtos.ShareAccessRequest body, HttpServletRequest request) {
        return noStore(response(HttpStatus.OK, service.grantShare(publicId, body, request), request));
    }

    @GetMapping("/public/shares/{publicId}")
    public ResponseEntity<V1Response<M6Dtos.SharedPicture>> sharedPicture(@PathVariable String publicId,
                                                                          HttpServletRequest request) {
        return noStore(response(HttpStatus.OK, service.sharedPicture(publicId, request, optionalUser(request)), request));
    }

    @GetMapping("/public/shares/{publicId}/content")
    public ResponseEntity<Resource> sharedContent(@PathVariable String publicId, HttpServletRequest request) {
        return file(service.shareContent(publicId, request, false, optionalUser(request)), false);
    }

    @GetMapping("/public/shares/{publicId}/download")
    public ResponseEntity<Resource> sharedDownload(@PathVariable String publicId, HttpServletRequest request) {
        return file(service.shareContent(publicId, request, true, auth.requireUser(request)), true);
    }

    @GetMapping("/pictures/{pictureId}/download")
    public ResponseEntity<Resource> privateDownload(@PathVariable String pictureId, HttpServletRequest request) {
        return file(service.pictureDownload(auth.requireUser(request), id(pictureId), false), true);
    }

    @GetMapping("/public/pictures/{pictureId}/download")
    public ResponseEntity<Resource> publicDownload(@PathVariable String pictureId, HttpServletRequest request) {
        return file(service.pictureDownload(auth.requireUser(request), id(pictureId), true), true);
    }

    @DeleteMapping("/pictures/{pictureId}/publication")
    public ResponseEntity<V1Response<M1Dtos.PictureDetail>> withdrawPublication(@PathVariable String pictureId,
                                                                                HttpServletRequest request) {
        User user = auth.requireUser(request);
        long id = id(pictureId);
        service.withdrawPublication(user, id);
        return response(HttpStatus.OK, auth.getPicture(user, id), request);
    }

    @GetMapping("/pictures/{pictureId}/comments")
    public ResponseEntity<V1Response<M6Dtos.CommentPage>> comments(@PathVariable String pictureId,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        return noStore(response(HttpStatus.OK, service.comments(auth.requireUser(request), id(pictureId), cursor, request), request));
    }

    @GetMapping("/pictures/{pictureId}/comment-mention-candidates")
    public ResponseEntity<V1Response<java.util.List<M6Dtos.MentionCandidate>>> mentionCandidates(
            @PathVariable String pictureId, @RequestParam(required = false) String q, HttpServletRequest request) {
        return noStore(response(HttpStatus.OK,
                service.mentionCandidates(auth.requireUser(request), id(pictureId), q, request), request));
    }

    @GetMapping("/public/pictures/{pictureId}/comments")
    public ResponseEntity<V1Response<M6Dtos.CommentPage>> publicComments(@PathVariable String pictureId,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        return noStore(response(HttpStatus.OK, service.publicComments(id(pictureId), cursor), request));
    }

    @GetMapping("/public/shares/{publicId}/comments")
    public ResponseEntity<V1Response<M6Dtos.CommentPage>> shareComments(@PathVariable String publicId,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        return noStore(response(HttpStatus.OK, service.shareComments(optionalUser(request), publicId, cursor, request), request));
    }

    @PostMapping("/pictures/{pictureId}/comments")
    public ResponseEntity<V1Response<M6Dtos.CommentView>> createComment(@PathVariable String pictureId,
            @RequestBody M6Dtos.CreateCommentRequest body, HttpServletRequest request) {
        return response(HttpStatus.CREATED, service.createComment(auth.requireUser(request), id(pictureId), body, request), request);
    }

    @PostMapping("/comments/{rootId}/replies")
    public ResponseEntity<V1Response<M6Dtos.CommentView>> reply(@PathVariable String rootId,
            @RequestBody M6Dtos.CreateReplyRequest body, HttpServletRequest request) {
        return response(HttpStatus.CREATED, service.reply(auth.requireUser(request), id(rootId), body, request), request);
    }

    @PatchMapping("/comments/{rootId}")
    public ResponseEntity<V1Response<M6Dtos.CommentView>> updateThread(@PathVariable String rootId,
            @RequestBody M6Dtos.UpdateThreadRequest body, HttpServletRequest request) {
        if (body == null || body.resolved() == null) throw V1Exception.badRequest("讨论状态不能为空");
        return response(HttpStatus.OK, service.setResolved(auth.requireUser(request), id(rootId), body.resolved()), request);
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<V1Response<Void>> deleteComment(@PathVariable String commentId, HttpServletRequest request) {
        service.deleteComment(auth.requireUser(request), id(commentId));
        return response(HttpStatus.OK, null, request);
    }

    private User optionalUser(HttpServletRequest request) {
        try { return auth.requireUser(request); }
        catch (V1Exception exception) { if (exception.getStatus() == HttpStatus.UNAUTHORIZED) return null; throw exception; }
    }

    private static ResponseEntity<Resource> file(M6Service.Download download, boolean attachment) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .contentLength(download.object().size()).contentType(download.mediaType());
        if (attachment) builder.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8).build().toString());
        return builder.body(download.object().resource());
    }

    private static <T> ResponseEntity<V1Response<T>> noStore(ResponseEntity<V1Response<T>> response) {
        HttpHeaders headers = new HttpHeaders(); headers.putAll(response.getHeaders());
        headers.setCacheControl(CacheControl.noStore()); headers.set("Referrer-Policy", "no-referrer");
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }

    private static long id(String value) {
        try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; }
        catch (RuntimeException exception) { throw V1Exception.badRequest("ID 格式无效"); }
    }

    private static <T> ResponseEntity<V1Response<T>> response(HttpStatus status, T data, HttpServletRequest request) {
        return ResponseEntity.status(status).body(V1Response.success(data, RequestIdFilter.get(request)));
    }
}
