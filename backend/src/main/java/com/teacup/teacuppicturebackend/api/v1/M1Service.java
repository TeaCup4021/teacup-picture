package com.teacup.teacuppicturebackend.api.v1;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.auth.SessionContext;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.PublishRequestMapper;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import com.teacup.teacuppicturebackend.mapper.PictureUploadPartMapper;
import com.teacup.teacuppicturebackend.mapper.PictureUploadSessionMapper;
import com.teacup.teacuppicturebackend.model.dto.user.UserRegisterRequest;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.PublishRequest;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.model.entity.PictureUploadPart;
import com.teacup.teacuppicturebackend.model.entity.PictureUploadSession;
import com.teacup.teacuppicturebackend.service.PersonalSpaceService;
import com.teacup.teacuppicturebackend.service.SpaceService;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureAssetService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import com.teacup.teacuppicturebackend.storage.ResumablePictureStorage;
import com.teacup.teacuppicturebackend.storage.UrlImportPreviewService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class M1Service {
    private static final List<String> OWNER_PERMISSIONS = List.of("picture:view", "picture:upload", "picture:edit", "picture:delete", "picture:publish", "picture:share");
    private final UserService userService;
    private final PersonalSpaceService personalSpaceService;
    private final SpaceService spaceService;
    private final PictureMapper pictureMapper;
    private final PublishRequestMapper publishRequestMapper;
    private final UserMapper userMapper;
    private final PictureStorage storage;
    private final UrlImportPreviewService urlPreviews;
    private final PictureAssetService assets;
    private final SpaceAccessService spaceAccess;
    private final PictureCurrentVersionService currentVersions;
    private final PictureUploadSessionMapper uploadSessionMapper;
    private final PictureUploadPartMapper uploadPartMapper;
    private final ResumablePictureStorage resumableStorage;

    @Autowired
    public M1Service(UserService userService, PersonalSpaceService personalSpaceService, SpaceService spaceService,
                     PictureMapper pictureMapper, PublishRequestMapper publishRequestMapper,
                      UserMapper userMapper, PictureStorage storage, PictureAssetService assets,
                       SpaceAccessService spaceAccess, PictureCurrentVersionService currentVersions,
                       UrlImportPreviewService urlPreviews, PictureUploadSessionMapper uploadSessionMapper,
                       PictureUploadPartMapper uploadPartMapper, ResumablePictureStorage resumableStorage) {
        this.userService = userService;
        this.personalSpaceService = personalSpaceService;
        this.spaceService = spaceService;
        this.pictureMapper = pictureMapper;
        this.publishRequestMapper = publishRequestMapper;
        this.userMapper = userMapper;
        this.storage = storage;
        this.urlPreviews = urlPreviews;
        this.assets = assets;
        this.spaceAccess = spaceAccess;
        this.currentVersions = currentVersions;
        this.uploadSessionMapper = uploadSessionMapper;
        this.uploadPartMapper = uploadPartMapper;
        this.resumableStorage = resumableStorage;
    }

    /** Kept for pre-M4 focused unit tests; runtime injection uses SpaceAccessService. */
    public M1Service(UserService userService, PersonalSpaceService personalSpaceService, SpaceService spaceService,
                     PictureMapper pictureMapper, PublishRequestMapper publishRequestMapper,
                     UserMapper userMapper, PictureStorage storage, PictureAssetService assets,
                     SpaceAccessService spaceAccess, PictureCurrentVersionService currentVersions,
                     UrlImportPreviewService urlPreviews) {
        this(userService, personalSpaceService, spaceService, pictureMapper, publishRequestMapper,
                userMapper, storage, assets, spaceAccess, currentVersions, urlPreviews, null, null, null);
    }

    /** Kept for pre-M4 focused unit tests; runtime injection uses SpaceAccessService. */
    public M1Service(UserService userService, PersonalSpaceService personalSpaceService, SpaceService spaceService,
                     PictureMapper pictureMapper, PublishRequestMapper publishRequestMapper,
                     UserMapper userMapper, PictureStorage storage, PictureAssetService assets) {
        this(userService, personalSpaceService, spaceService, pictureMapper, publishRequestMapper,
                userMapper, storage, assets, null, null, null, null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.UploadSessionView createUploadSession(User user, M1Dtos.UploadSessionCreateRequest request) {
        if (resumableStorage == null || uploadSessionMapper == null || uploadPartMapper == null) {
            throw V1Exception.serviceUnavailable("分片上传暂不可用");
        }
        if (request == null || request.fileName() == null || request.fileName().isBlank()) throw V1Exception.badRequest("文件名不能为空");
        if (request.totalSize() < 1 || request.totalSize() > 20L * 1024 * 1024) throw new V1Exception(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, 41300, "图片不能超过 20 MB");
        int chunkSize = request.chunkSize() == null ? 5 * 1024 * 1024 : request.chunkSize();
        if (chunkSize != 5 * 1024 * 1024) throw V1Exception.badRequest("分片大小必须为 5 MiB");
        int totalParts = (int) ((request.totalSize() + chunkSize - 1) / chunkSize);
        if (totalParts < 1 || totalParts > 10000) throw V1Exception.badRequest("分片数量无效");
        Space space = resolveSpace(user, request.spaceId(), "upload");
        if (space.getTotalCount() >= space.getMaxCount() || space.getTotalSize() + request.totalSize() > space.getMaxSize()) {
            throw V1Exception.conflict("个人空间容量不足");
        }
        PictureUploadSession session = new PictureUploadSession();
        session.setUserId(user.getId()); session.setSpaceId(space.getId());
        session.setStoragePrefix(resumableStorage.createUpload(space.getId()));
        session.setFileName(request.fileName().trim()); session.setContentType(request.contentType());
        session.setName(blankToNull(request.name())); session.setIntroduction(blankToNull(request.introduction()));
        session.setCategory(blankToNull(request.category())); session.setTags(JSONUtil.toJsonStr(request.tags() == null ? List.of() : request.tags()));
        session.setTotalSize(request.totalSize()); session.setChunkSize(chunkSize); session.setTotalParts(totalParts);
        session.setFileChecksum(blankToNull(request.fileChecksum())); session.setStatus("active");
        session.setExpiresAt(LocalDateTime.now().plusHours(24));
        uploadSessionMapper.insert(session);
        return uploadSessionView(session, List.of());
    }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.UploadSessionView uploadPart(User user, long sessionId, int partNumber,
                                               java.io.InputStream input, long size, String checksum) {
        PictureUploadSession session = requireUploadSession(user, sessionId);
        if (partNumber < 1 || partNumber > session.getTotalParts()) throw V1Exception.badRequest("分片序号无效");
        long expectedSize = partNumber == session.getTotalParts()
                ? session.getTotalSize() - (long) session.getChunkSize() * (session.getTotalParts() - 1)
                : session.getChunkSize();
        if (size != expectedSize) throw V1Exception.badRequest("分片大小不匹配");
        PictureUploadPart existing = uploadPartMapper.selectOne(new LambdaQueryWrapper<PictureUploadPart>()
                .eq(PictureUploadPart::getSessionId, sessionId).eq(PictureUploadPart::getPartNumber, partNumber));
        if (existing != null) {
            if (existing.getSize() == size && (checksum == null || checksum.isBlank() || checksum.equalsIgnoreCase(existing.getChecksum()))) {
                return uploadSessionView(session, uploadPartMapper.selectBySessionId(sessionId).stream().map(PictureUploadPart::getPartNumber).toList());
            }
            throw V1Exception.conflict("分片已存在且内容不一致");
        }
        String actualChecksum = resumableStorage.uploadPart(session.getStoragePrefix(), partNumber, input, size, checksum);
        PictureUploadPart part = new PictureUploadPart(); part.setSessionId(sessionId); part.setPartNumber(partNumber);
        part.setEtag(actualChecksum); part.setSize(size); part.setChecksum(actualChecksum); uploadPartMapper.insert(part);
        return uploadSessionView(session, uploadPartMapper.selectBySessionId(sessionId).stream().map(PictureUploadPart::getPartNumber).toList());
    }

    public M1Dtos.UploadSessionView getUploadSession(User user, long sessionId) {
        PictureUploadSession session = requireUploadSession(user, sessionId);
        return uploadSessionView(session, uploadPartMapper.selectBySessionId(sessionId).stream().map(PictureUploadPart::getPartNumber).toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.PictureDetail completeUploadSession(User user, long sessionId, M1Dtos.UploadSessionCreateRequest metadata) {
        PictureUploadSession session = requireUploadSession(user, sessionId);
        List<PictureUploadPart> rows = uploadPartMapper.selectBySessionId(sessionId);
        if (rows.size() != session.getTotalParts()) throw V1Exception.conflict("仍有分片未上传");
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).getPartNumber() != i + 1) throw V1Exception.conflict("分片序号不完整");
        List<ResumablePictureStorage.Part> parts = rows.stream().map(p -> new ResumablePictureStorage.Part(p.getPartNumber(), p.getEtag(), p.getSize())).toList();
        PictureStorage.StoredPicture stored = resumableStorage.completeUpload(session.getStoragePrefix(), parts, session.getFileName(), session.getContentType(), session.getSpaceId());
        Space space = resolveSpace(user, Long.toString(session.getSpaceId()), "upload");
        M1Dtos.PictureDetail detail;
        try {
            detail = savePictureWithCompensation(user, space, stored,
                    metadata == null || metadata.name() == null ? (session.getName() == null ? session.getFileName() : session.getName()) : metadata.name(),
                    metadata == null ? session.getIntroduction() : metadata.introduction(),
                    metadata == null ? session.getCategory() : metadata.category(),
                    metadata == null ? (session.getTags() == null ? List.of() : JSONUtil.toList(session.getTags(), String.class)) : metadata.tags());
            session.setStatus("completed"); session.setCompletedPictureId(Long.parseLong(detail.id())); uploadSessionMapper.updateById(session);
            return detail;
        } catch (RuntimeException exception) {
            session.setStatus("failed"); uploadSessionMapper.updateById(session);
            throw exception;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void abortUploadSession(User user, long sessionId) {
        PictureUploadSession session = requireUploadSession(user, sessionId);
        List<PictureUploadPart> parts = uploadPartMapper.selectBySessionId(sessionId);
        resumableStorage.abortUpload(session.getStoragePrefix(), parts.stream().map(PictureUploadPart::getPartNumber).toList());
        session.setStatus("aborted"); uploadSessionMapper.updateById(session);
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredUploadSessions() {
        if (uploadSessionMapper == null || uploadPartMapper == null || resumableStorage == null) return;
        for (PictureUploadSession session : uploadSessionMapper.selectExpiredActive()) {
            List<PictureUploadPart> parts = uploadPartMapper.selectBySessionId(session.getId());
            resumableStorage.abortUpload(session.getStoragePrefix(), parts.stream().map(PictureUploadPart::getPartNumber).toList());
            session.setStatus("expired"); uploadSessionMapper.updateById(session);
        }
    }

    private PictureUploadSession requireUploadSession(User user, long sessionId) {
        PictureUploadSession session = uploadSessionMapper == null ? null : uploadSessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), user.getId())) throw V1Exception.notFound();
        if (!"active".equals(session.getStatus())) throw V1Exception.conflict("上传会话已结束");
        if (session.getExpiresAt() == null || session.getExpiresAt().isBefore(LocalDateTime.now())) throw V1Exception.conflict("上传会话已过期");
        return session;
    }

    private M1Dtos.UploadSessionView uploadSessionView(PictureUploadSession session, List<Integer> uploadedParts) {
        return new M1Dtos.UploadSessionView(Long.toString(session.getId()), session.getChunkSize(), session.getTotalParts(),
                session.getTotalSize(), uploadedParts.stream().sorted().toList(), session.getExpiresAt().toInstant(java.time.ZoneOffset.UTC), session.getStatus());
    }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.RegistrationResult register(M1Dtos.RegisterRequest request) {
        if (request == null || request.account() == null || request.password() == null || request.passwordConfirmation() == null) throw V1Exception.badRequest("注册参数不完整");
        if (request.account().length() < 4 || request.account().length() > 64) throw V1Exception.badRequest("账号长度必须为 4 到 64 位");
        if (request.password().length() < 8 || request.password().length() > 72) throw V1Exception.badRequest("密码长度必须为 8 到 72 位");
        if (!request.password().equals(request.passwordConfirmation())) throw V1Exception.badRequest("两次输入的密码不一致");
        UserRegisterRequest legacy = new UserRegisterRequest();
        legacy.setUserAccount(request.account());
        legacy.setUserPassword(request.password());
        legacy.setCheckPassword(request.passwordConfirmation());
        try {
            long userId = userService.userRegister(legacy);
            Space personal = personalSpaceService.getOrCreatePersonalSpace(userId);
            return new M1Dtos.RegistrationResult(Long.toString(userId), personal.getId().toString());
        } catch (com.teacup.teacuppicturebackend.exception.BusinessException exception) {
            if (exception.getMessage().contains("重复")) throw V1Exception.conflict("账号已存在");
            throw V1Exception.badRequest(exception.getMessage());
        }
    }

    public User login(M1Dtos.LoginRequest input, HttpServletRequest request) {
        if (input == null || input.account() == null || input.password() == null) throw V1Exception.badRequest("登录参数不完整");
        if (input.account().length() < 4 || input.account().length() > 64
                || input.password().length() < 8 || input.password().length() > 72) {
            throw V1Exception.unauthorized();
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUserAccount, input.account())
                .eq(User::getUserPassword, userService.getEncryptPassword(input.password()))
                .eq(User::getIsDelete, 0)
                .last("LIMIT 1"));
        if (user == null) throw V1Exception.unauthorized();
        SessionContext.setLoginState(request.getSession(true), request, user);
        request.changeSessionId();
        return user;
    }

    public User requireUser(HttpServletRequest request) {
        try { return userService.getLoginUser(request); }
        catch (RuntimeException exception) { throw V1Exception.unauthorized(); }
    }

    public M1Dtos.CurrentUser currentUser(User user) {
        return new M1Dtos.CurrentUser(id(user.getId()), user.getUserAccount(), user.getUserName(), user.getUserAvatar(),
                user.getUserProfile(), user.getUserRole(), instant(user.getCreateTime()));
    }

    public M1Dtos.PersonalSpace personalSpace(User user) {
        Space space = personalSpaceService.getOrCreatePersonalSpace(user.getId());
        return new M1Dtos.PersonalSpace(id(space.getId()), space.getSpaceName(), "personal", level(space.getSpaceLevel()),
                nz(space.getMaxSize()), nz(space.getMaxCount()), nz(space.getTotalSize()), nz(space.getTotalCount()),
                OWNER_PERMISSIONS, instant(space.getCreateTime()), instant(space.getUpdateTime()));
    }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.PictureDetail upload(User user, MultipartFile file, String spaceId, String name,
                                       String introduction, String category, List<String> tags) {
        Space space = resolveSpace(user, spaceId, "upload");
        PictureStorage.StoredPicture stored = storage.store(file, space.getId());
        return savePictureWithCompensation(user, space, stored, name, introduction, category, tags);
    }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.PictureDetail importUrl(User user, M1Dtos.UrlImportRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) throw V1Exception.badRequest("图片 URL 不能为空");
        Space space = resolveSpace(user, request.spaceId(), "upload");
        PictureStorage.StoredPicture stored = request.previewToken() == null || request.previewToken().isBlank()
                ? storage.importUrl(request.url(), space.getId())
                : importPreviewedUrl(user, request, space);
        return savePictureWithCompensation(user, space, stored, request.name(), request.introduction(), request.category(), request.tags());
    }

    public UrlImportPreviewService.Preview previewUrl(User user, String url) {
        if (url == null || url.isBlank()) throw V1Exception.badRequest("图片 URL 不能为空");
        return urlPreviews.preview(user, url.trim());
    }

    @Transactional(rollbackFor = Exception.class)
    public Picture saveGeneratedPicture(User user, PictureStorage.StoredPicture stored, String name,
                                        String introduction, List<String> tags) {
        Space space = personalSpaceService.getOrCreatePersonalSpace(user.getId());
        M1Dtos.PictureDetail detail = savePictureWithCompensation(user, space, stored, name, introduction, "AI 创作", tags);
        return requirePicture(Long.parseLong(detail.id()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void discardGeneratedPicture(Picture picture) {
        if (picture == null || picture.getId() == null) return;
        pictureMapper.deleteById(picture.getId());
        spaceService.lambdaUpdate().eq(Space::getId, picture.getSpaceId())
                .setSql("totalSize = GREATEST(0, totalSize - " + nz(picture.getPicSize()) + ")")
                .setSql("totalCount = GREATEST(0, totalCount - 1)").update();
        storage.delete(picture.getObjectKey());
        storage.delete(picture.getThumbnailObjectKey());
    }

    public M1Dtos.PicturePage listPictures(User user, int page, int pageSize, String spaceId) {
        validatePage(page, pageSize);
        Space space = resolveSpace(user, spaceId, "view");
        Page<Picture> result = pictureMapper.selectPage(new Page<>(page, pageSize), new LambdaQueryWrapper<Picture>()
                .eq(Picture::getSpaceId, space.getId()).eq(Picture::getIsDelete, 0)
                .orderByDesc(Picture::getCreateTime).orderByDesc(Picture::getId));
        List<M1Dtos.PictureSummary> items = result.getRecords().stream().map(p -> summary(p, userMapper.selectById(p.getUserId()))).toList();
        return new M1Dtos.PicturePage(items, new M1Dtos.PageMeta(page, pageSize, result.getTotal(), result.getPages()));
    }

    public M1Dtos.PictureDetail getPicture(User user, long pictureId) {
        Picture picture = picture(user, pictureId, "view");
        return detail(picture, userMapper.selectById(picture.getUserId()), user);
    }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.PublishRequestView requestPublication(User user, long pictureId) {
        Picture picture = picture(user, pictureId, "edit");
        if ("pending".equals(picture.getPublishStatus())) throw V1Exception.conflict("图片已有待审核申请");
        if ("approved".equals(picture.getPublishStatus())) throw V1Exception.conflict("图片已经公开");
        PublishRequest request = new PublishRequest();
        request.setPictureId(picture.getId()); request.setRequesterId(user.getId()); request.setStatus("pending");
        publishRequestMapper.insert(request);
        picture.setPublishStatus("pending"); picture.setVisibility("private");
        pictureMapper.updateById(picture);
        return publishView(request, picture, user, null);
    }

    public M1Dtos.PublishRequestPage listPublishRequests(User admin, int page, int pageSize, String status, Long pictureId) {
        requireAdmin(admin); validatePage(page, pageSize);
        LambdaQueryWrapper<PublishRequest> query = new LambdaQueryWrapper<PublishRequest>().orderByDesc(PublishRequest::getCreateTime).orderByDesc(PublishRequest::getId);
        if (status != null && !status.isBlank()) query.eq(PublishRequest::getStatus, status);
        if (pictureId != null) query.eq(PublishRequest::getPictureId, pictureId);
        Page<PublishRequest> result = publishRequestMapper.selectPage(new Page<>(page, pageSize), query);
        List<M1Dtos.PublishRequestView> items = result.getRecords().stream().map(this::publishView).toList();
        return new M1Dtos.PublishRequestPage(items, new M1Dtos.PageMeta(page, pageSize, result.getTotal(), result.getPages()));
    }

    public M1Dtos.PublishRequestView getPublishRequest(User admin, long requestId) { requireAdmin(admin); return publishView(requireRequest(requestId)); }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.PublishRequestView decide(User admin, long requestId, boolean approve, String reason) {
        requireAdmin(admin);
        PublishRequest request = requireRequest(requestId);
        if (!"pending".equals(request.getStatus())) throw V1Exception.conflict("申请已处理");
        if (!approve && (reason == null || reason.isBlank())) throw V1Exception.badRequest("拒绝原因不能为空");
        Picture picture = requirePicture(request.getPictureId());
        LocalDateTime now = LocalDateTime.now();
        request.setStatus(approve ? "approved" : "rejected"); request.setReviewerId(admin.getId());
        request.setDecisionReason(blankToNull(reason)); request.setReviewTime(now); publishRequestMapper.updateById(request);
        picture.setPublishStatus(request.getStatus()); picture.setVisibility(approve ? "public" : "private");
        picture.setPublishedAt(approve ? new Date() : null); picture.setReviewerId(admin.getId());
        picture.setReviewMessage(blankToNull(reason)); picture.setReviewTime(new Date()); picture.setReviewStatus(approve ? 1 : 2);
        pictureMapper.updateById(picture);
        return publishView(request, picture, userMapper.selectById(request.getRequesterId()), admin);
    }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.PictureDetail withdraw(User admin, long pictureId, String reason) {
        requireAdmin(admin);
        if (reason == null || reason.isBlank()) throw V1Exception.badRequest("撤回原因不能为空");
        Picture picture = requirePicture(pictureId);
        if (!"approved".equals(picture.getPublishStatus()) || !"public".equals(picture.getVisibility())) throw V1Exception.conflict("图片当前未公开");
        PublishRequest latest = publishRequestMapper.selectOne(new LambdaQueryWrapper<PublishRequest>()
                .eq(PublishRequest::getPictureId, pictureId).eq(PublishRequest::getStatus, "approved")
                .orderByDesc(PublishRequest::getCreateTime).last("LIMIT 1"));
        if (latest == null) throw V1Exception.conflict("缺少可撤回的审核记录");
        latest.setStatus("withdrawn"); latest.setReviewerId(admin.getId()); latest.setDecisionReason(reason); latest.setReviewTime(LocalDateTime.now());
        publishRequestMapper.updateById(latest);
        picture.setPublishStatus("withdrawn"); picture.setVisibility("private"); picture.setPublishedAt(null);
        picture.setReviewerId(admin.getId()); picture.setReviewMessage(reason); picture.setReviewTime(new Date());
        pictureMapper.updateById(picture);
        return detail(picture, userMapper.selectById(picture.getUserId()));
    }

    public M1Dtos.PublicPictureCursorPage publicPictures(String cursor, int limit) {
        if (limit < 1 || limit > 50) throw V1Exception.badRequest("limit 必须为 1 到 50");
        Cursor decoded = decodeCursor(cursor);
        LambdaQueryWrapper<Picture> query = new LambdaQueryWrapper<Picture>()
                .eq(Picture::getVisibility, "public").eq(Picture::getPublishStatus, "approved").eq(Picture::getIsDelete, 0);
        if (decoded != null) query.and(q -> q.lt(Picture::getPublishedAt, decoded.publishedAt()).or(n -> n.eq(Picture::getPublishedAt, decoded.publishedAt()).lt(Picture::getId, decoded.id())));
        query.orderByDesc(Picture::getPublishedAt).orderByDesc(Picture::getId).last("LIMIT " + (limit + 1));
        List<Picture> rows = pictureMapper.selectList(query); boolean hasMore = rows.size() > limit;
        if (hasMore) rows = rows.subList(0, limit);
        List<M1Dtos.PublicPictureSummary> items = rows.stream().map(p -> publicSummary(p, userMapper.selectById(p.getUserId()))).toList();
        String next = hasMore && !rows.isEmpty() ? encodeCursor(rows.get(rows.size() - 1)) : null;
        return new M1Dtos.PublicPictureCursorPage(items, next, hasMore);
    }

    public M1Dtos.PublicPictureDetail publicPicture(long pictureId) {
        Picture picture = requirePicture(pictureId);
        if (!"public".equals(picture.getVisibility()) || !"approved".equals(picture.getPublishStatus())) throw V1Exception.notFound();
        User author = userMapper.selectById(picture.getUserId()); M1Dtos.PublicPictureSummary summary = publicSummary(picture, author);
        return new M1Dtos.PublicPictureDetail(summary.id(), summary.thumbnailUrl(), summary.name(), summary.introduction(), summary.category(), summary.tags(), summary.width(), summary.height(), summary.dominantColor(), summary.author(), summary.publishedAt(), assets.publicUrl(picture.getId(), "original"), nz(picture.getPicSize()), picture.getPicFormat(), id(picture.getCurrentVersionId()));
    }

    private M1Dtos.PictureDetail savePictureWithCompensation(User user, Space space, PictureStorage.StoredPicture stored,
                                                              String name, String introduction, String category, List<String> tags) {
        try {
            return savePicture(user, space, stored, name, introduction, category, tags);
        } catch (RuntimeException exception) {
            storage.delete(stored.objectKey());
            storage.delete(stored.thumbnailObjectKey());
            throw exception;
        }
    }

    private PictureStorage.StoredPicture importPreviewedUrl(User user, M1Dtos.UrlImportRequest request, Space space) {
        if (urlPreviews == null) throw V1Exception.badRequest("预览已失效，请重新预览图片 URL");
        try (UrlImportPreviewService.ClaimedPreview preview = urlPreviews.claim(user, request.previewToken(), request.url());
             java.io.InputStream input = preview.openStream()) {
            return storage.store(input, preview.fileName(), preview.contentType(), space.getId());
        } catch (java.io.IOException exception) {
            throw V1Exception.badRequest("预览已失效，请重新预览图片 URL");
        }
    }

    private M1Dtos.PictureDetail savePicture(User user, Space space, PictureStorage.StoredPicture stored, String name, String introduction, String category, List<String> tags) {
        if (space.getTotalCount() >= space.getMaxCount() || space.getTotalSize() + stored.size() > space.getMaxSize()) throw V1Exception.conflict("个人空间容量不足");
        Picture picture = new Picture(); picture.setId(IdWorker.getId());
        picture.setUrl(assets.privateUrl(picture.getId(), "original")); picture.setThumbnailUrl(assets.privateUrl(picture.getId(), "thumbnail"));
        picture.setStorageProvider("minio"); picture.setObjectKey(stored.objectKey()); picture.setContentType(stored.contentType()); picture.setChecksum(stored.checksum());
        picture.setThumbnailObjectKey(stored.thumbnailObjectKey());
        picture.setName(name == null || name.isBlank() ? "未命名图片" : name.trim()); picture.setIntroduction(blankToNull(introduction));
        picture.setCategory(blankToNull(category)); picture.setTags(JSONUtil.toJsonStr(tags == null ? List.of() : tags));
        picture.setPicSize(stored.size()); picture.setPicWidth(stored.width()); picture.setPicHeight(stored.height());
        picture.setPicScale(Math.round(stored.width() * 100.0 / stored.height()) / 100.0); picture.setPicFormat(stored.format());
        picture.setUserId(user.getId()); picture.setSpaceId(space.getId()); picture.setVisibility("private"); picture.setPublishStatus("not_requested"); picture.setReviewStatus(0);
        pictureMapper.insert(picture);
        if (currentVersions != null) currentVersions.createInitial(picture, user.getId(), "original");
        spaceService.lambdaUpdate().eq(Space::getId, space.getId()).setSql("totalSize = totalSize + " + stored.size()).setSql("totalCount = totalCount + 1").update();
        return detail(picture, user);
    }

    private Space resolveSpace(User user, String spaceId, String action) {
        Space space = spaceId == null || spaceId.isBlank() ? personalSpaceService.getOrCreatePersonalSpace(user.getId()) : spaceService.getById(parseId(spaceId));
        if (space == null) throw V1Exception.notFound();
        if (spaceAccess == null) {
            if (!Objects.equals(space.getUserId(), user.getId())) throw V1Exception.forbidden();
        } else if (("upload".equals(action) && !spaceAccess.canUpload(user, space)) ||
                ("view".equals(action) && !spaceAccess.canView(user, space))) {
            throw V1Exception.forbidden();
        }
        return space;
    }
    private Picture picture(User user, long pictureId, String action) {
        Picture picture = requirePicture(pictureId);
        if (spaceAccess == null) {
            if (!Objects.equals(picture.getUserId(), user.getId()) && !userService.isAdmin(user)) throw V1Exception.notFound();
            return picture;
        }
        Space space = spaceService.getById(picture.getSpaceId());
        if (space == null || ("view".equals(action) ? !spaceAccess.canView(user, space) : !spaceAccess.canEdit(user, space))) {
            throw V1Exception.notFound();
        }
        return picture;
    }
    private Picture requirePicture(long id) { Picture p = pictureMapper.selectById(id); if (p == null || Integer.valueOf(1).equals(p.getIsDelete())) throw V1Exception.notFound(); return p; }
    private PublishRequest requireRequest(long id) { PublishRequest r = publishRequestMapper.selectById(id); if (r == null) throw V1Exception.notFound(); return r; }
    private void requireAdmin(User user) { if (!userService.isAdmin(user)) throw V1Exception.forbidden(); }
    private void validatePage(int page, int pageSize) { if (page < 1 || pageSize < 1 || pageSize > 100) throw V1Exception.badRequest("分页参数无效"); }
    private M1Dtos.PublishRequestView publishView(PublishRequest request) { Picture picture = requirePicture(request.getPictureId()); return publishView(request, picture, userMapper.selectById(request.getRequesterId()), request.getReviewerId() == null ? null : userMapper.selectById(request.getReviewerId())); }
    private M1Dtos.PublishRequestView publishView(PublishRequest request, Picture picture, User requester, User reviewer) { return new M1Dtos.PublishRequestView(id(request.getId()), summary(picture, userMapper.selectById(picture.getUserId())), author(requester), request.getStatus(), reviewer == null ? null : author(reviewer), request.getDecisionReason(), instant(request.getCreateTime()), instant(request.getReviewTime())); }
    private M1Dtos.PictureSummary summary(Picture p, User author) { return new M1Dtos.PictureSummary(id(p.getId()), id(p.getSpaceId()), assets.privateUrl(p.getId(), "thumbnail"), p.getName(), p.getIntroduction(), p.getCategory(), tags(p), nz(p.getPicSize()), nzi(p.getPicWidth()), nzi(p.getPicHeight()), p.getPicFormat(), p.getPicColor(), p.getVisibility(), p.getPublishStatus(), author(author), instant(p.getCreateTime()), instant(p.getUpdateTime())); }
    private M1Dtos.PictureDetail detail(Picture p, User author) { return detail(p, author, author); }
    private M1Dtos.PictureDetail detail(Picture p, User author, User viewer) {
        M1Dtos.PictureSummary s = summary(p, author);
        List<String> permissions = OWNER_PERMISSIONS;
        if (spaceAccess != null && p.getSpaceId() != null) {
            Space space = spaceService.getById(p.getSpaceId());
            permissions = space == null ? List.of() : spaceAccess.permissions(viewer, space);
        }
        return new M1Dtos.PictureDetail(s.id(), s.spaceId(), s.thumbnailUrl(), s.name(), s.introduction(), s.category(), s.tags(), s.size(), s.width(), s.height(), s.format(), s.dominantColor(), s.visibility(), s.publishStatus(), s.author(), s.createdAt(), s.updatedAt(), assets.privateUrl(p.getId(), "original"), permissions, id(p.getCurrentVersionId()), "rejected".equals(p.getPublishStatus()) ? p.getReviewMessage() : null, instant(p.getReviewTime()));
    }
    private M1Dtos.PublicPictureSummary publicSummary(Picture p, User user) { return new M1Dtos.PublicPictureSummary(id(p.getId()), assets.publicUrl(p.getId(), "thumbnail"), p.getName(), p.getIntroduction(), p.getCategory(), tags(p), nzi(p.getPicWidth()), nzi(p.getPicHeight()), p.getPicColor(), author(user), instant(p.getPublishedAt())); }
    private M1Dtos.AuthorSummary author(User u) { return new M1Dtos.AuthorSummary(id(u.getId()), u.getUserName(), u.getUserAvatar()); }
    private List<String> tags(Picture p) { return p.getTags() == null || p.getTags().isBlank() ? List.of() : JSONUtil.toList(p.getTags(), String.class); }
    private String encodeCursor(Picture p) { String raw = p.getPublishedAt().getTime() + ":" + p.getId(); return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8)); }
    private Cursor decodeCursor(String cursor) { if (cursor == null || cursor.isBlank()) return null; try { String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2); return new Cursor(new Date(Long.parseLong(values[0])), Long.parseLong(values[1])); } catch (RuntimeException e) { throw V1Exception.badRequest("cursor 无效"); } }
    private static long parseId(String value) { try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; } catch (NumberFormatException e) { throw V1Exception.badRequest("ID 格式无效"); } }
    private static String id(Long value) { return value == null ? null : value.toString(); }
    private static long nz(Long value) { return value == null ? 0 : value; }
    private static int nzi(Integer value) { return value == null ? 1 : value; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String level(Integer value) { return value != null && value == 1 ? "professional" : value != null && value == 2 ? "flagship" : "common"; }
    private static Instant instant(Date value) { return value == null ? null : value.toInstant(); }
    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private record Cursor(Date publishedAt, long id) {}
}
