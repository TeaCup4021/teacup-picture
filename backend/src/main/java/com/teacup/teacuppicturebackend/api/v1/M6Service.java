package com.teacup.teacuppicturebackend.api.v1;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.api.v1.model.M6Dtos;
import com.teacup.teacuppicturebackend.mapper.*;
import com.teacup.teacuppicturebackend.model.entity.*;
import com.teacup.teacuppicturebackend.model.enums.SpaceTypeEnum;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class M6Service {
    private static final String SHARE_GRANTS = "M6_SHARE_GRANTS";
    private static final int COMMENT_LIMIT = 20;
    private static final int MAX_REPLIES = 100;
    private final PictureMapper pictures;
    private final PictureShareMapper shares;
    private final PictureCommentMapper comments;
    private final CommentMentionMapper mentions;
    private final NotificationMapper notifications;
    private final UserMapper users;
    private final SpaceMapper spaces;
    private final SpaceUserMapper members;
    private final PublishRequestMapper publishRequests;
    private final UserService userService;
    private final SpaceAccessService access;
    private final PictureCurrentVersionService currentVersions;
    private final PictureStorage storage;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(11);

    public M6Service(PictureMapper pictures, PictureShareMapper shares,
                     PictureCommentMapper comments, CommentMentionMapper mentions, NotificationMapper notifications,
                     UserMapper users, SpaceMapper spaces, SpaceUserMapper members, PublishRequestMapper publishRequests,
                     UserService userService, SpaceAccessService access, PictureCurrentVersionService currentVersions,
                     PictureStorage storage, StringRedisTemplate redis, ObjectMapper json) {
        this.pictures = pictures; this.shares = shares; this.comments = comments;
        this.mentions = mentions; this.notifications = notifications; this.users = users; this.spaces = spaces;
        this.members = members; this.publishRequests = publishRequests; this.userService = userService;
        this.access = access; this.currentVersions = currentVersions; this.storage = storage; this.redis = redis; this.json = json;
    }

    public M6Dtos.ShareView activeShare(User user, long pictureId) {
        requireShareManager(user, requirePicture(pictureId));
        PictureShare share = shares.selectOne(new LambdaQueryWrapper<PictureShare>()
                .eq(PictureShare::getPictureId, pictureId).isNull(PictureShare::getRevokedAt).last("LIMIT 1"));
        return share == null || isExpired(share) ? null : shareView(share, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public M6Dtos.ShareView createShare(User user, long pictureId, M6Dtos.CreateShareRequest input, boolean regenerate) {
        lockPicture(pictureId);
        Picture picture = requirePicture(pictureId);
        requireShareManager(user, picture);
        PictureShare active = shares.selectOne(new LambdaQueryWrapper<PictureShare>()
                .eq(PictureShare::getPictureId, pictureId).isNull(PictureShare::getRevokedAt).last("FOR UPDATE"));
        if (active != null && isExpired(active)) {
            active.setRevokedAt(new Date());
            shares.updateById(active);
            active = null;
        }
        if (active != null && !regenerate) throw V1Exception.conflict("图片已有活动分享链接");
        if (active != null) { active.setRevokedAt(new Date()); shares.updateById(active); }
        Instant expiry = validateExpiry(input == null ? null : input.expiresAt());
        String password = input == null ? null : trimPassword(input.password());
        String publicId = randomToken(16);
        String secret = randomToken(32);
        PictureShare share = new PictureShare();
        share.setPictureId(pictureId); share.setCreatorId(user.getId()); share.setPublicId(publicId);
        share.setSecretHash(sha256(secret));
        share.setPasswordHash(password == null ? null : passwords.encode(password));
        share.setExpiresAt(expiry == null ? null : Date.from(expiry));
        shares.insert(share);
        return shareView(share, "/shares/" + publicId + "#" + secret);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeShare(User user, long pictureId, long shareId) {
        lockPicture(pictureId);
        requireShareManager(user, requirePicture(pictureId));
        PictureShare share = shares.selectById(shareId);
        if (share == null || !Objects.equals(share.getPictureId(), pictureId)) throw V1Exception.notFound();
        if (share.getRevokedAt() == null) { share.setRevokedAt(new Date()); shares.updateById(share); }
    }

    public M6Dtos.ShareAccessResult grantShare(String publicId, M6Dtos.ShareAccessRequest input, HttpServletRequest request) {
        PictureShare share = requireActiveShare(publicId);
        String secret = input == null ? null : input.secret();
        if (secret == null || !constantEquals(share.getSecretHash(), sha256(secret))) throw V1Exception.notFound();
        if (hasGrant(request.getSession(false), share)) {
            return new M6Dtos.ShareAccessResult(true, share.getPasswordHash() != null, instant(share.getExpiresAt()));
        }
        if (share.getPasswordHash() != null) {
            String key = "m6:share-password:" + publicId + ":" + request.getRemoteAddr();
            try {
                Long attempts = redis.opsForValue().increment(key);
                if (attempts != null && attempts == 1) redis.expire(key, 10, TimeUnit.MINUTES);
                if (attempts != null && attempts > 5) throw V1Exception.tooManyRequests("分享密码尝试次数过多，请稍后重试");
                if (input.password() == null || !passwords.matches(input.password(), share.getPasswordHash())) {
                    throw V1Exception.passwordRequired();
                }
                redis.delete(key);
            } catch (V1Exception exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw V1Exception.serviceUnavailable("分享访问校验暂时不可用，请稍后重试");
            }
        }
        grant(request.getSession(true), share);
        return new M6Dtos.ShareAccessResult(true, share.getPasswordHash() != null, instant(share.getExpiresAt()));
    }

    public M6Dtos.SharedPicture sharedPicture(String publicId, HttpServletRequest request, User user) {
        PictureShare share = requireGrantedShare(publicId, request);
        Picture picture = requirePicture(share.getPictureId());
        User author = users.selectById(picture.getUserId());
        return new M6Dtos.SharedPicture(id(picture.getId()), picture.getName(), picture.getIntroduction(),
                picture.getCategory(), tags(picture), width(picture), height(picture), authorSummary(author),
                "/api/v1/public/shares/" + publicId + "/content", id(picture.getCurrentVersionId()),
                user != null, user != null);
    }

    public Download shareContent(String publicId, HttpServletRequest request, boolean download, User user) {
        PictureShare share = requireGrantedShare(publicId, request);
        if (download && user == null) throw V1Exception.unauthorized();
        return download(requirePicture(share.getPictureId()));
    }

    public Download pictureDownload(User user, long pictureId, boolean publicOnly) {
        Picture picture = requirePicture(pictureId);
        if (publicOnly) {
            if (!isPublic(picture)) throw V1Exception.notFound();
        } else if (!canView(user, picture, null)) throw V1Exception.notFound();
        return download(picture);
    }

    @Transactional(rollbackFor = Exception.class)
    public void withdrawPublication(User user, long pictureId) {
        lockPicture(pictureId);
        Picture picture = requirePicture(pictureId);
        requireShareManager(user, picture);
        if (!isPublic(picture)) throw V1Exception.conflict("图片当前未公开");
        PublishRequest latest = publishRequests.selectOne(new LambdaQueryWrapper<PublishRequest>()
                .eq(PublishRequest::getPictureId, pictureId).eq(PublishRequest::getStatus, "approved")
                .orderByDesc(PublishRequest::getCreateTime).last("LIMIT 1"));
        if (latest != null) { latest.setStatus("withdrawn"); latest.setDecisionReason("图片所有者主动撤回"); publishRequests.updateById(latest); }
        picture.setVisibility("private"); picture.setPublishStatus("withdrawn"); picture.setPublishedAt(null);
        picture.setReviewMessage("图片所有者主动撤回"); picture.setReviewTime(new Date());
        pictures.updateById(picture);
    }

    public M6Dtos.CommentPage comments(User viewer, long pictureId, String cursor, HttpServletRequest request) {
        Picture picture = requirePicture(pictureId);
        if (!canView(viewer, picture, request)) throw V1Exception.notFound();
        return commentPage(viewer, picture, cursor);
    }

    public M6Dtos.CommentPage publicComments(long pictureId, String cursor) {
        Picture picture = requirePicture(pictureId);
        if (!isPublic(picture)) throw V1Exception.notFound();
        return commentPage(null, picture, cursor);
    }

    public M6Dtos.CommentPage shareComments(User viewer, String publicId, String cursor, HttpServletRequest request) {
        PictureShare share = requireGrantedShare(publicId, request);
        return commentPage(viewer, requirePicture(share.getPictureId()), cursor);
    }

    public List<M6Dtos.MentionCandidate> mentionCandidates(User viewer, long pictureId, String query,
                                                            HttpServletRequest request) {
        Picture picture = requirePicture(pictureId);
        if (!canView(viewer, picture, request)) throw V1Exception.notFound();

        LinkedHashSet<Long> candidateIds = new LinkedHashSet<>();
        candidateIds.add(picture.getUserId());
        if (picture.getSpaceId() != null) {
            Space space = spaces.selectById(picture.getSpaceId());
            if (space != null) candidateIds.add(space.getOwnerId());
            members.selectList(new LambdaQueryWrapper<SpaceUser>()
                            .eq(SpaceUser::getSpaceId, picture.getSpaceId()).select(SpaceUser::getUserId))
                    .forEach(member -> candidateIds.add(member.getUserId()));
        }
        comments.selectList(new LambdaQueryWrapper<PictureComment>()
                        .eq(PictureComment::getPictureId, pictureId).select(PictureComment::getAuthorId))
                .forEach(comment -> candidateIds.add(comment.getAuthorId()));
        candidateIds.remove(null);
        candidateIds.remove(viewer.getId());
        if (candidateIds.isEmpty()) return List.of();

        String keyword = query == null ? "" : query.trim();
        if (keyword.length() > 50) throw V1Exception.badRequest("搜索关键词不能超过 50 字");
        LambdaQueryWrapper<User> usersQuery = new LambdaQueryWrapper<User>()
                .in(User::getId, candidateIds).ne(User::getIsDelete, 1)
                .orderByAsc(User::getUserName).last("LIMIT 50");
        if (!keyword.isEmpty()) usersQuery.like(User::getUserName, keyword);
        return users.selectList(usersQuery).stream()
                .map(candidate -> new M6Dtos.MentionCandidate(id(candidate.getId()),
                        candidate.getUserName(), candidate.getUserAvatar()))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public M6Dtos.CommentView createComment(User user, long pictureId, M6Dtos.CreateCommentRequest input, HttpServletRequest request) {
        limitComment(user, request);
        Picture picture = requirePicture(pictureId);
        if (!canView(user, picture, request)) throw V1Exception.notFound();
        String kind = input == null ? null : input.kind();
        if (!Set.of("comment", "annotation").contains(kind)) throw V1Exception.badRequest("评论类型无效");
        String body = body(input.body());
        PictureComment row = new PictureComment();
        row.setPictureId(pictureId); row.setAuthorId(user.getId()); row.setKind(kind); row.setBody(body); row.setStatus("active");
        if ("annotation".equals(kind)) {
            PictureVersion current = currentVersions.requireCurrent(picture);
            long supplied = parseId(input.pictureVersionId());
            if (!Objects.equals(current.getId(), supplied)) throw V1Exception.conflict("图片版本已更新，请刷新后重新批注");
            row.setPictureVersionId(current.getId());
            row.setPositionX(coordinate(input.x())); row.setPositionY(coordinate(input.y()));
        }
        comments.insert(row);
        saveMentions(user, picture, row, input.mentionedUserIds());
        if (!Objects.equals(picture.getUserId(), user.getId())) notifyUser(picture.getUserId(),
                "picture_comment_created", user.getId(), "picture_comment", row.getId(), Map.of("pictureId", id(pictureId), "pictureName", picture.getName()));
        return view(row, user, picture, List.of(), 0);
    }

    @Transactional(rollbackFor = Exception.class)
    public M6Dtos.CommentView reply(User user, long rootId, M6Dtos.CreateReplyRequest input, HttpServletRequest request) {
        limitComment(user, request);
        PictureComment root = requireRoot(rootId);
        Picture picture = requirePicture(root.getPictureId());
        if (!canView(user, picture, request)) throw V1Exception.notFound();
        if (input == null) throw V1Exception.badRequest("回复参数不完整");
        PictureComment replyTo = input.replyToId() != null ? requireComment(parseId(input.replyToId())) : root;
        if (!Objects.equals(replyTo.getPictureId(), picture.getId()) || !(Objects.equals(replyTo.getId(), rootId) || Objects.equals(replyTo.getRootId(), rootId))) throw V1Exception.badRequest("回复目标无效");
        PictureComment row = new PictureComment();
        row.setPictureId(picture.getId()); row.setPictureVersionId(root.getPictureVersionId()); row.setAuthorId(user.getId());
        row.setRootId(rootId); row.setReplyToId(replyTo.getId()); row.setKind("reply"); row.setBody(body(input.body())); row.setStatus("active");
        comments.insert(row);
        Set<Long> notified = new HashSet<>();
        if (!Objects.equals(replyTo.getAuthorId(), user.getId())) {
            notifyUser(replyTo.getAuthorId(), "picture_comment_reply", user.getId(), "picture_comment", row.getId(), Map.of("pictureId", id(picture.getId()), "rootId", id(rootId), "pictureName", picture.getName()));
            notified.add(replyTo.getAuthorId());
        }
        saveMentions(user, picture, row, input.mentionedUserIds(), notified);
        return view(row, user, picture, List.of(), 0);
    }

    @Transactional(rollbackFor = Exception.class)
    public M6Dtos.CommentView setResolved(User user, long rootId, boolean resolved) {
        PictureComment root = requireRoot(rootId);
        Picture picture = requirePicture(root.getPictureId());
        if (!canResolve(user, picture, root)) throw V1Exception.forbidden();
        Date resolvedAt = resolved ? new Date() : null;
        Long resolvedBy = resolved ? user.getId() : null;
        comments.update(null, new UpdateWrapper<PictureComment>()
                .eq("id", rootId)
                .set("resolvedAt", resolvedAt)
                .set("resolvedBy", resolvedBy));
        root.setResolvedAt(resolvedAt); root.setResolvedBy(resolvedBy);
        if (!Objects.equals(root.getAuthorId(), user.getId())) notifyUser(root.getAuthorId(),
                resolved ? "picture_annotation_resolved" : "picture_annotation_reopened", user.getId(),
                "picture_comment", root.getId(), Map.of("pictureId", id(picture.getId()), "pictureName", picture.getName()));
        return view(root, user, picture, replies(root.getId()), replyCount(root.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(User user, long commentId) {
        PictureComment row = requireComment(commentId);
        Picture picture = requirePicture(row.getPictureId());
        if (!Objects.equals(row.getAuthorId(), user.getId()) && !canManageComments(user, picture)) throw V1Exception.forbidden();
        row.setStatus("deleted"); row.setBody(""); comments.updateById(row);
        mentions.delete(new LambdaQueryWrapper<CommentMention>().eq(CommentMention::getCommentId, commentId));
    }

    private M6Dtos.CommentPage commentPage(User viewer, Picture picture, String cursor) {
        long before = cursor == null || cursor.isBlank() ? Long.MAX_VALUE : parseId(cursor);
        List<PictureComment> rows = comments.selectList(new LambdaQueryWrapper<PictureComment>()
                .eq(PictureComment::getPictureId, picture.getId()).isNull(PictureComment::getRootId)
                .lt(PictureComment::getId, before).orderByDesc(PictureComment::getId).last("LIMIT " + (COMMENT_LIMIT + 1)));
        boolean more = rows.size() > COMMENT_LIMIT;
        if (more) rows = rows.subList(0, COMMENT_LIMIT);
        List<M6Dtos.CommentView> items = rows.stream().map(row -> view(row, viewer, picture, replies(row.getId()), replyCount(row.getId()))).toList();
        String next = more && !rows.isEmpty() ? id(rows.get(rows.size() - 1).getId()) : null;
        return new M6Dtos.CommentPage(items, next, more, id(picture.getCurrentVersionId()));
    }

    private List<PictureComment> replies(long rootId) {
        return comments.selectList(new LambdaQueryWrapper<PictureComment>().eq(PictureComment::getRootId, rootId)
                .orderByAsc(PictureComment::getId).last("LIMIT " + MAX_REPLIES));
    }

    private long replyCount(long rootId) { return comments.selectCount(new LambdaQueryWrapper<PictureComment>().eq(PictureComment::getRootId, rootId)); }

    private M6Dtos.CommentView view(PictureComment row, User viewer, Picture picture, List<PictureComment> replyRows, long replyCount) {
        boolean deleted = "deleted".equals(row.getStatus());
        User author = users.selectById(row.getAuthorId());
        List<M6Dtos.CommentView> replyViews = replyRows.stream().map(reply -> view(reply, viewer, picture, List.of(), 0)).toList();
        return new M6Dtos.CommentView(id(row.getId()), id(row.getPictureId()), id(row.getPictureVersionId()), row.getKind(),
                deleted ? "" : row.getBody(), decimal(row.getPositionX()), decimal(row.getPositionY()), author(author),
                deleted, row.getResolvedAt() != null, id(row.getResolvedBy()), instant(row.getResolvedAt()),
                instant(row.getCreateTime()), instant(row.getUpdateTime()), id(row.getReplyToId()), replyViews, replyCount,
                viewer != null && (Objects.equals(row.getAuthorId(), viewer.getId()) || canManageComments(viewer, picture)),
                viewer != null && row.getRootId() == null && canResolve(viewer, picture, row));
    }

    private void saveMentions(User actor, Picture picture, PictureComment comment, List<String> ids) { saveMentions(actor, picture, comment, ids, new HashSet<>()); }
    private void saveMentions(User actor, Picture picture, PictureComment comment, List<String> ids, Set<Long> alreadyNotified) {
        if (ids == null) return;
        for (Long userId : ids.stream().filter(Objects::nonNull).map(this::parseId).distinct().limit(20).toList()) {
            if (Objects.equals(userId, actor.getId())) continue;
            if (!canMention(userId, picture, comment)) throw V1Exception.badRequest("被提及用户不在当前讨论范围内");
            CommentMention mention = new CommentMention(); mention.setCommentId(comment.getId()); mention.setUserId(userId); mentions.insert(mention);
            if (alreadyNotified.add(userId)) notifyUser(userId, "picture_comment_mention", actor.getId(), "picture_comment", comment.getId(), Map.of("pictureId", id(picture.getId()), "pictureName", picture.getName()));
        }
    }

    private boolean canMention(long userId, Picture picture, PictureComment comment) {
        User target = users.selectById(userId);
        if (target == null || Integer.valueOf(1).equals(target.getIsDelete())) return false;
        if (Objects.equals(userId, picture.getUserId())) return true;
        if (picture.getSpaceId() != null && members.selectCount(new LambdaQueryWrapper<SpaceUser>()
                .eq(SpaceUser::getSpaceId, picture.getSpaceId()).eq(SpaceUser::getUserId, userId)) > 0) return true;
        return comments.selectCount(new LambdaQueryWrapper<PictureComment>()
                .eq(PictureComment::getPictureId, picture.getId()).eq(PictureComment::getAuthorId, userId)) > 0;
    }

    private void notifyUser(long recipient, String type, long actor, String resourceType, long resourceId, Map<String, Object> payload) {
        if (recipient == actor) return;
        Notification row = new Notification(); row.setUserId(recipient); row.setType(type); row.setActorId(actor);
        row.setResourceType(resourceType); row.setResourceId(resourceId);
        try { row.setPayload(json.writeValueAsString(payload)); } catch (Exception e) { throw new IllegalStateException("通知序列化失败", e); }
        notifications.insert(row);
    }

    private boolean canView(User user, Picture picture, HttpServletRequest request) {
        if (isPublic(picture)) return true;
        if (user != null && picture.getSpaceId() != null) {
            Space space = spaces.selectById(picture.getSpaceId());
            if (space != null && access.canView(user, space)) return true;
        }
        return request != null && hasPictureGrant(request.getSession(false), picture.getId());
    }

    private void requireShareManager(User user, Picture picture) {
        if (Objects.equals(picture.getUserId(), user.getId()) && isPersonal(picture)) return;
        Space space = picture.getSpaceId() == null ? null : spaces.selectById(picture.getSpaceId());
        String role = space == null ? null : access.roleOf(user, space);
        if (!SpaceAccessService.OWNER.equals(role) && !SpaceAccessService.ADMIN.equals(role)) throw V1Exception.forbidden();
    }

    private boolean canManageComments(User user, Picture picture) {
        if (userService.isAdmin(user) && isPublic(picture)) return true;
        if (Objects.equals(picture.getUserId(), user.getId()) && isPersonal(picture)) return true;
        Space space = picture.getSpaceId() == null ? null : spaces.selectById(picture.getSpaceId());
        String role = space == null ? null : access.roleOf(user, space);
        return SpaceAccessService.OWNER.equals(role) || SpaceAccessService.ADMIN.equals(role);
    }

    private boolean canResolve(User user, Picture picture, PictureComment root) {
        if (Objects.equals(root.getAuthorId(), user.getId()) || canManageComments(user, picture)) return true;
        Space space = picture.getSpaceId() == null ? null : spaces.selectById(picture.getSpaceId());
        return space != null && SpaceAccessService.EDITOR.equals(access.roleOf(user, space));
    }

    private boolean isPersonal(Picture picture) {
        Space space = picture.getSpaceId() == null ? null : spaces.selectById(picture.getSpaceId());
        return space != null && Integer.valueOf(SpaceTypeEnum.PRIVATE.getValue()).equals(space.getSpaceType());
    }

    private Picture requirePicture(long id) { Picture p = pictures.selectById(id); if (p == null || Integer.valueOf(1).equals(p.getIsDelete())) throw V1Exception.notFound(); return p; }
    private PictureComment requireComment(long id) { PictureComment c = comments.selectById(id); if (c == null) throw V1Exception.notFound(); return c; }
    private PictureComment requireRoot(long id) { PictureComment c = requireComment(id); if (c.getRootId() != null) throw V1Exception.badRequest("操作仅适用于讨论串"); return c; }
    private PictureShare requireActiveShare(String publicId) { PictureShare s = shares.selectOne(new LambdaQueryWrapper<PictureShare>().eq(PictureShare::getPublicId, publicId).last("LIMIT 1")); if (s == null || s.getRevokedAt() != null || (s.getExpiresAt() != null && !s.getExpiresAt().after(new Date()))) throw V1Exception.notFound(); requirePicture(s.getPictureId()); return s; }
    private PictureShare requireGrantedShare(String publicId, HttpServletRequest request) { PictureShare s = requireActiveShare(publicId); if (!hasGrant(request.getSession(false), s)) throw V1Exception.notFound(); return s; }
    private void lockPicture(long pictureId) { if (pictures.lockPictureForUpdate(pictureId) == null) throw V1Exception.notFound(); }
    private Download download(Picture p) { if (p.getObjectKey() == null) throw V1Exception.notFound(); PictureStorage.StoredObject object = storage.load(p.getObjectKey()); return new Download(object, fileName(p, object.fileName()), MediaType.parseMediaType(object.contentType())); }
    private static boolean isPublic(Picture p) { return "public".equals(p.getVisibility()) && "approved".equals(p.getPublishStatus()); }
    private static int width(Picture p) { return p.getPicWidth() == null ? 1 : p.getPicWidth(); }
    private static int height(Picture p) { return p.getPicHeight() == null ? 1 : p.getPicHeight(); }
    private static List<String> tags(Picture p) { return p.getTags() == null || p.getTags().isBlank() ? List.of() : JSONUtil.toList(p.getTags(), String.class); }
    private static M6Dtos.CommentAuthor author(User u) { return u == null ? new M6Dtos.CommentAuthor(null, "未知用户", null) : new M6Dtos.CommentAuthor(id(u.getId()), u.getUserName(), u.getUserAvatar()); }
    private static M1Dtos.AuthorSummary authorSummary(User u) { return u == null ? new M1Dtos.AuthorSummary(null, "未知用户", null) : new M1Dtos.AuthorSummary(id(u.getId()), u.getUserName(), u.getUserAvatar()); }

    private void grant(HttpSession session, PictureShare share) { Map<String, Long> grants = grants(session); grants.put(share.getPublicId(), share.getExpiresAt() == null ? Long.MAX_VALUE : share.getExpiresAt().getTime()); session.setAttribute(SHARE_GRANTS, grants); }
    @SuppressWarnings("unchecked") private Map<String, Long> grants(HttpSession session) { Object value = session.getAttribute(SHARE_GRANTS); return value instanceof Map<?, ?> ? new HashMap<>((Map<String, Long>) value) : new HashMap<>(); }
    private boolean hasGrant(HttpSession session, PictureShare share) { if (session == null) return false; Long expiry = grants(session).get(share.getPublicId()); return expiry != null && expiry > System.currentTimeMillis(); }
    private boolean hasPictureGrant(HttpSession session, long pictureId) { if (session == null) return false; for (String publicId : grants(session).keySet()) { PictureShare s = shares.selectOne(new LambdaQueryWrapper<PictureShare>().eq(PictureShare::getPublicId, publicId).last("LIMIT 1")); if (s != null && Objects.equals(s.getPictureId(), pictureId) && s.getRevokedAt() == null && (s.getExpiresAt() == null || s.getExpiresAt().after(new Date()))) return true; } return false; }

    private void limitComment(User user, HttpServletRequest request) {
        String key = "m6:comment:" + user.getId() + ":" + request.getRemoteAddr();
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, 1, TimeUnit.MINUTES);
            if (count != null && count > 20) throw V1Exception.tooManyRequests("评论过于频繁，请稍后重试");
        } catch (V1Exception exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw V1Exception.serviceUnavailable("评论服务暂时不可用，请稍后重试");
        }
    }
    private static Instant validateExpiry(Instant value) { if (value == null) return null; Instant now = Instant.now(); if (!value.isAfter(now.plus(Duration.ofMinutes(1))) || value.isAfter(now.plus(Duration.ofDays(365)))) throw V1Exception.badRequest("分享有效期必须在 1 分钟到 365 天之间"); return value; }
    private static boolean isExpired(PictureShare share) { return share.getExpiresAt() != null && !share.getExpiresAt().after(new Date()); }
    private static String trimPassword(String value) { if (value == null || value.isBlank()) return null; if (value.length() < 4 || value.length() > 72) throw V1Exception.badRequest("分享密码长度必须为 4 到 72 位"); return value; }
    private String randomToken(int bytes) { byte[] value = new byte[bytes]; random.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static boolean constantEquals(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII)); }
    private static String body(String value) { String body = value == null ? "" : value.trim(); if (body.isEmpty() || body.length() > 2000) throw V1Exception.badRequest("评论长度必须为 1 到 2000 字"); return body; }
    private static BigDecimal coordinate(Double value) { if (value == null || !Double.isFinite(value) || value < 0 || value > 1) throw V1Exception.badRequest("批注坐标无效"); return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP); }
    private static Double decimal(BigDecimal value) { return value == null ? null : value.doubleValue(); }
    private long parseId(String value) { try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; } catch (RuntimeException e) { throw V1Exception.badRequest("ID 格式无效"); } }
    private static String id(Long value) { return value == null ? null : value.toString(); }
    private static Instant instant(Date value) { return value == null ? null : value.toInstant(); }
    private static String fileName(Picture p, String fallback) { String ext = p.getPicFormat() == null ? "bin" : p.getPicFormat(); String base = p.getName() == null || p.getName().isBlank() ? "picture" : p.getName().replaceAll("[\\\\/:*?\"<>|]", "_"); return base + "." + ext; }
    private M6Dtos.ShareView shareView(PictureShare s, String path) { return new M6Dtos.ShareView(id(s.getId()), id(s.getPictureId()), s.getPublicId(), path, s.getPasswordHash() != null, instant(s.getExpiresAt()), instant(s.getRevokedAt()), instant(s.getCreateTime())); }
    public record Download(PictureStorage.StoredObject object, String fileName, MediaType mediaType) {}
}
