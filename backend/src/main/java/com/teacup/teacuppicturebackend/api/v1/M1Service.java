package com.teacup.teacuppicturebackend.api.v1;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.PublishRequestMapper;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import com.teacup.teacuppicturebackend.model.dto.user.UserRegisterRequest;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.PublishRequest;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.PersonalSpaceService;
import com.teacup.teacuppicturebackend.service.SpaceService;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureAssetService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.springframework.stereotype.Service;
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

import static com.teacup.teacuppicturebackend.constant.UserConstant.USER_LOGIN_STATE;

@Service
public class M1Service {
    private static final List<String> OWNER_PERMISSIONS = List.of("picture:view", "picture:upload", "picture:edit", "picture:delete", "picture:publish");
    private final UserService userService;
    private final PersonalSpaceService personalSpaceService;
    private final SpaceService spaceService;
    private final PictureMapper pictureMapper;
    private final PublishRequestMapper publishRequestMapper;
    private final UserMapper userMapper;
    private final PictureStorage storage;
    private final PictureAssetService assets;

    public M1Service(UserService userService, PersonalSpaceService personalSpaceService, SpaceService spaceService,
                     PictureMapper pictureMapper, PublishRequestMapper publishRequestMapper,
                     UserMapper userMapper, PictureStorage storage, PictureAssetService assets) {
        this.userService = userService;
        this.personalSpaceService = personalSpaceService;
        this.spaceService = spaceService;
        this.pictureMapper = pictureMapper;
        this.publishRequestMapper = publishRequestMapper;
        this.userMapper = userMapper;
        this.storage = storage;
        this.assets = assets;
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
        request.getSession(true).setAttribute(USER_LOGIN_STATE, user);
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
        Space space = resolveOwnedSpace(user, spaceId);
        PictureStorage.StoredPicture stored = storage.store(file, space.getId());
        return savePictureWithCompensation(user, space, stored, name, introduction, category, tags);
    }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.PictureDetail importUrl(User user, M1Dtos.UrlImportRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) throw V1Exception.badRequest("图片 URL 不能为空");
        Space space = resolveOwnedSpace(user, request.spaceId());
        PictureStorage.StoredPicture stored = storage.importUrl(request.url(), space.getId());
        return savePictureWithCompensation(user, space, stored, request.name(), request.introduction(), request.category(), request.tags());
    }

    @Transactional(rollbackFor = Exception.class)
    public Picture saveGeneratedPicture(User user, PictureStorage.StoredPicture stored, String name,
                                        String introduction, List<String> tags) {
        Space space = personalSpaceService.getOrCreatePersonalSpace(user.getId());
        M1Dtos.PictureDetail detail = savePictureWithCompensation(user, space, stored, name, introduction, "AI 创作", tags);
        return requirePicture(Long.parseLong(detail.id()));
    }

    public M1Dtos.PicturePage listPictures(User user, int page, int pageSize, String spaceId) {
        validatePage(page, pageSize);
        Space space = resolveOwnedSpace(user, spaceId);
        Page<Picture> result = pictureMapper.selectPage(new Page<>(page, pageSize), new LambdaQueryWrapper<Picture>()
                .eq(Picture::getSpaceId, space.getId()).eq(Picture::getIsDelete, 0)
                .orderByDesc(Picture::getCreateTime).orderByDesc(Picture::getId));
        List<M1Dtos.PictureSummary> items = result.getRecords().stream().map(p -> summary(p, userMapper.selectById(p.getUserId()))).toList();
        return new M1Dtos.PicturePage(items, new M1Dtos.PageMeta(page, pageSize, result.getTotal(), result.getPages()));
    }

    public M1Dtos.PictureDetail getPicture(User user, long pictureId) {
        Picture picture = ownedPicture(user, pictureId);
        return detail(picture, userMapper.selectById(picture.getUserId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public M1Dtos.PublishRequestView requestPublication(User user, long pictureId) {
        Picture picture = ownedPicture(user, pictureId);
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
        return new M1Dtos.PublicPictureDetail(summary.id(), summary.thumbnailUrl(), summary.name(), summary.introduction(), summary.category(), summary.tags(), summary.width(), summary.height(), summary.dominantColor(), summary.author(), summary.publishedAt(), assets.publicUrl(picture.getId(), "original"), nz(picture.getPicSize()), picture.getPicFormat());
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
        spaceService.lambdaUpdate().eq(Space::getId, space.getId()).setSql("totalSize = totalSize + " + stored.size()).setSql("totalCount = totalCount + 1").update();
        return detail(picture, user);
    }

    private Space resolveOwnedSpace(User user, String spaceId) {
        Space space = spaceId == null || spaceId.isBlank() ? personalSpaceService.getOrCreatePersonalSpace(user.getId()) : spaceService.getById(parseId(spaceId));
        if (space == null) throw V1Exception.notFound();
        if (!Objects.equals(space.getUserId(), user.getId())) throw V1Exception.forbidden();
        return space;
    }
    private Picture ownedPicture(User user, long pictureId) { Picture picture = requirePicture(pictureId); if (!Objects.equals(picture.getUserId(), user.getId()) && !userService.isAdmin(user)) throw V1Exception.notFound(); return picture; }
    private Picture requirePicture(long id) { Picture p = pictureMapper.selectById(id); if (p == null || Integer.valueOf(1).equals(p.getIsDelete())) throw V1Exception.notFound(); return p; }
    private PublishRequest requireRequest(long id) { PublishRequest r = publishRequestMapper.selectById(id); if (r == null) throw V1Exception.notFound(); return r; }
    private void requireAdmin(User user) { if (!userService.isAdmin(user)) throw V1Exception.forbidden(); }
    private void validatePage(int page, int pageSize) { if (page < 1 || pageSize < 1 || pageSize > 100) throw V1Exception.badRequest("分页参数无效"); }
    private M1Dtos.PublishRequestView publishView(PublishRequest request) { Picture picture = requirePicture(request.getPictureId()); return publishView(request, picture, userMapper.selectById(request.getRequesterId()), request.getReviewerId() == null ? null : userMapper.selectById(request.getReviewerId())); }
    private M1Dtos.PublishRequestView publishView(PublishRequest request, Picture picture, User requester, User reviewer) { return new M1Dtos.PublishRequestView(id(request.getId()), summary(picture, userMapper.selectById(picture.getUserId())), author(requester), request.getStatus(), reviewer == null ? null : author(reviewer), request.getDecisionReason(), instant(request.getCreateTime()), instant(request.getReviewTime())); }
    private M1Dtos.PictureSummary summary(Picture p, User author) { return new M1Dtos.PictureSummary(id(p.getId()), id(p.getSpaceId()), assets.privateUrl(p.getId(), "thumbnail"), p.getName(), p.getIntroduction(), p.getCategory(), tags(p), nz(p.getPicSize()), nzi(p.getPicWidth()), nzi(p.getPicHeight()), p.getPicFormat(), p.getPicColor(), p.getVisibility(), p.getPublishStatus(), author(author), instant(p.getCreateTime()), instant(p.getUpdateTime())); }
    private M1Dtos.PictureDetail detail(Picture p, User author) { M1Dtos.PictureSummary s = summary(p, author); return new M1Dtos.PictureDetail(s.id(), s.spaceId(), s.thumbnailUrl(), s.name(), s.introduction(), s.category(), s.tags(), s.size(), s.width(), s.height(), s.format(), s.dominantColor(), s.visibility(), s.publishStatus(), s.author(), s.createdAt(), s.updatedAt(), assets.privateUrl(p.getId(), "original"), OWNER_PERMISSIONS, "rejected".equals(p.getPublishStatus()) ? p.getReviewMessage() : null, instant(p.getReviewTime())); }
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
