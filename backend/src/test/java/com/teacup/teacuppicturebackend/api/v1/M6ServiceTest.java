package com.teacup.teacuppicturebackend.api.v1;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.teacup.teacuppicturebackend.api.v1.model.M6Dtos;
import com.teacup.teacuppicturebackend.mapper.*;
import com.teacup.teacuppicturebackend.model.entity.*;
import com.teacup.teacuppicturebackend.model.enums.SpaceTypeEnum;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class M6ServiceTest {
    private final PictureMapper pictures = mock(PictureMapper.class);
    private final PictureShareMapper shares = mock(PictureShareMapper.class);
    private final PictureCommentMapper comments = mock(PictureCommentMapper.class);
    private final CommentMentionMapper mentions = mock(CommentMentionMapper.class);
    private final NotificationMapper notifications = mock(NotificationMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final SpaceMapper spaces = mock(SpaceMapper.class);
    private final SpaceUserMapper members = mock(SpaceUserMapper.class);
    private final PublishRequestMapper publishRequests = mock(PublishRequestMapper.class);
    private final UserService userService = mock(UserService.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    private final PictureCurrentVersionService currentVersions = mock(PictureCurrentVersionService.class);
    private final PictureStorage storage = mock(PictureStorage.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private M6Service service;
    private User owner;
    private Picture picture;
    private Space personal;

    @BeforeEach
    void setUp() {
        service = new M6Service(pictures, shares, comments, mentions, notifications, users, spaces,
                members, publishRequests, userService, access, currentVersions, storage, redis,
                new com.fasterxml.jackson.databind.ObjectMapper());
        owner = new User(); owner.setId(10L); owner.setUserName("Owner"); owner.setIsDelete(0);
        picture = new Picture(); picture.setId(100L); picture.setUserId(10L); picture.setSpaceId(20L);
        picture.setName("Cup"); picture.setObjectKey("spaces/20/cup.png"); picture.setPicFormat("png");
        picture.setPicWidth(1200); picture.setPicHeight(800); picture.setCurrentVersionId(300L); picture.setIsDelete(0);
        personal = new Space(); personal.setId(20L); personal.setSpaceType(SpaceTypeEnum.PRIVATE.getValue()); personal.setOwnerId(10L);
        when(pictures.selectById(100L)).thenReturn(picture);
        when(pictures.lockPictureForUpdate(100L)).thenReturn(100L);
        when(spaces.selectById(20L)).thenReturn(personal);
        when(access.canView(owner, personal)).thenReturn(true);
        when(access.roleOf(owner, personal)).thenReturn(SpaceAccessService.OWNER);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
    }

    @Test
    void createShareStoresOnlyHashesAndReturnsFragmentSecretOnce() {
        when(shares.insert(any(PictureShare.class))).thenAnswer(invocation -> { ((PictureShare) invocation.getArgument(0)).setId(40L); return 1; });

        M6Dtos.ShareView result = service.createShare(owner, 100L,
                new M6Dtos.CreateShareRequest(Instant.now().plusSeconds(86400), "review-123"), false);

        assertTrue(result.sharePath().matches("/shares/[A-Za-z0-9_-]{22}#[A-Za-z0-9_-]{43}"));
        var captor = org.mockito.ArgumentCaptor.forClass(PictureShare.class);
        verify(shares).insert(captor.capture());
        PictureShare saved = captor.getValue();
        assertEquals(64, saved.getSecretHash().length());
        assertFalse(result.sharePath().contains(saved.getSecretHash()));
        assertNotEquals("review-123", saved.getPasswordHash());
    }

    @Test
    void annotationRejectsStaleVersion() {
        PictureVersion current = new PictureVersion(); current.setId(300L); current.setPictureId(100L);
        when(currentVersions.requireCurrent(picture)).thenReturn(current);

        V1Exception error = assertThrows(V1Exception.class, () -> service.createComment(owner, 100L,
                new M6Dtos.CreateCommentRequest("annotation", "杯把高光过强", "299", 0.75, 0.4, List.of()),
                new MockHttpServletRequest()));

        assertEquals(40901, error.getCode());
        verify(comments, never()).insert(any(PictureComment.class));
    }

    @Test
    void annotationUsesNormalizedCoordinatesAndCurrentVersion() {
        PictureVersion current = new PictureVersion(); current.setId(300L); current.setPictureId(100L);
        when(currentVersions.requireCurrent(picture)).thenReturn(current);
        when(comments.insert(any(PictureComment.class))).thenAnswer(invocation -> { ((PictureComment) invocation.getArgument(0)).setId(50L); return 1; });
        when(users.selectById(10L)).thenReturn(owner);

        M6Dtos.CommentView result = service.createComment(owner, 100L,
                new M6Dtos.CreateCommentRequest("annotation", "杯把高光过强", "300", 0.75, 0.4, List.of()),
                new MockHttpServletRequest());

        assertEquals("300", result.pictureVersionId());
        assertEquals(0.75, result.x());
        verify(comments).insert(org.mockito.ArgumentMatchers.<PictureComment>argThat(row -> row.getPositionX().toPlainString().equals("0.75000000")));
    }

    @Test
    void reopeningThreadExplicitlyClearsResolvedColumns() {
        PictureComment root = new PictureComment();
        root.setId(50L); root.setPictureId(100L); root.setAuthorId(owner.getId());
        root.setKind("annotation"); root.setBody("已处理"); root.setResolvedAt(new Date());
        root.setResolvedBy(owner.getId()); root.setCreateTime(new Date()); root.setUpdateTime(new Date());
        when(comments.selectById(50L)).thenReturn(root);
        when(users.selectById(owner.getId())).thenReturn(owner);

        M6Dtos.CommentView result = service.setResolved(owner, 50L, false);

        assertFalse(result.resolved());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<UpdateWrapper<PictureComment>> update =
                org.mockito.ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(comments).update(isNull(), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("resolvedAt"));
        assertTrue(update.getValue().getSqlSet().contains("resolvedBy"));
        assertEquals(2, update.getValue().getParamNameValuePairs().size());
        assertTrue(update.getValue().getParamNameValuePairs().values().stream().allMatch(Objects::isNull));
    }

    @Test
    void shareManagerRejectsUnrelatedUser() {
        User stranger = new User(); stranger.setId(99L);
        assertEquals(40101, assertThrows(V1Exception.class,
                () -> service.createShare(stranger, 100L, new M6Dtos.CreateShareRequest(null, null), false)).getCode());
        verify(shares, never()).insert(any(PictureShare.class));
    }

    @Test
    void expiredShareIsRevokedBeforeCreatingAReplacement() {
        PictureShare expired = new PictureShare(); expired.setId(41L); expired.setPictureId(100L);
        expired.setExpiresAt(Date.from(Instant.now().minusSeconds(60)));
        when(shares.selectOne(any())).thenReturn(expired);
        when(shares.insert(any(PictureShare.class))).thenAnswer(invocation -> { ((PictureShare) invocation.getArgument(0)).setId(42L); return 1; });

        M6Dtos.ShareView result = service.createShare(owner, 100L,
                new M6Dtos.CreateShareRequest(Instant.now().plusSeconds(86400), null), false);

        assertEquals("42", result.id());
        assertNotNull(expired.getRevokedAt());
        verify(shares).updateById(expired);
    }

    @Test
    void expiredOrRevokedShareCannotBeGranted() {
        PictureShare expired = new PictureShare(); expired.setPictureId(100L); expired.setPublicId("expired");
        expired.setExpiresAt(Date.from(Instant.now().minusSeconds(1)));
        when(shares.selectOne(any())).thenReturn(expired);
        assertEquals(40400, assertThrows(V1Exception.class, () -> service.grantShare("expired",
                new M6Dtos.ShareAccessRequest("secret", null), new MockHttpServletRequest())).getCode());

        PictureShare revoked = new PictureShare(); revoked.setPictureId(100L); revoked.setPublicId("revoked");
        revoked.setRevokedAt(new Date());
        when(shares.selectOne(any())).thenReturn(revoked);
        assertEquals(40400, assertThrows(V1Exception.class, () -> service.grantShare("revoked",
                new M6Dtos.ShareAccessRequest("secret", null), new MockHttpServletRequest())).getCode());
    }

    @Test
    void passwordShareRequiresPasswordThenGrantsSessionAccess() {
        when(shares.insert(any(PictureShare.class))).thenAnswer(invocation -> { ((PictureShare) invocation.getArgument(0)).setId(43L); return 1; });
        M6Dtos.ShareView created = service.createShare(owner, 100L,
                new M6Dtos.CreateShareRequest(Instant.now().plusSeconds(86400), "review-123"), false);
        String secret = created.sharePath().substring(created.sharePath().indexOf('#') + 1);
        var captor = org.mockito.ArgumentCaptor.forClass(PictureShare.class);
        verify(shares).insert(captor.capture());
        when(shares.selectOne(any())).thenReturn(captor.getValue());
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertEquals(40102, assertThrows(V1Exception.class, () -> service.grantShare(captor.getValue().getPublicId(),
                new M6Dtos.ShareAccessRequest(secret, "wrong-password"), request)).getCode());
        assertTrue(service.grantShare(captor.getValue().getPublicId(),
                new M6Dtos.ShareAccessRequest(secret, "review-123"), request).granted());
        assertNotNull(request.getSession(false));
        assertTrue(service.grantShare(captor.getValue().getPublicId(),
                new M6Dtos.ShareAccessRequest(secret, null), request).granted());
    }

    @Test
    void redisFailureClosesCommentCreationWithServiceUnavailable() {
        when(values.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        V1Exception error = assertThrows(V1Exception.class, () -> service.createComment(owner, 100L,
                new M6Dtos.CreateCommentRequest("comment", "反馈", null, null, null, List.of()),
                new MockHttpServletRequest()));

        assertEquals(50300, error.getCode());
        verify(comments, never()).insert(any(PictureComment.class));
    }

    @Test
    void commentRejectsMentionOutsideThePictureDiscussion() {
        when(comments.insert(any(PictureComment.class))).thenAnswer(invocation -> { ((PictureComment) invocation.getArgument(0)).setId(50L); return 1; });

        V1Exception error = assertThrows(V1Exception.class, () -> service.createComment(owner, 100L,
                new M6Dtos.CreateCommentRequest("comment", "请看一下", null, null, null, List.of("99")),
                new MockHttpServletRequest()));

        assertEquals(40000, error.getCode());
        verify(mentions, never()).insert(any(CommentMention.class));
    }

    @Test
    void commentOnAnotherUsersPictureCreatesNavigableNotification() {
        User viewer = new User(); viewer.setId(11L); viewer.setUserName("Viewer"); viewer.setIsDelete(0);
        when(access.canView(viewer, personal)).thenReturn(true);
        when(comments.insert(any(PictureComment.class))).thenAnswer(invocation -> {
            ((PictureComment) invocation.getArgument(0)).setId(53L);
            return 1;
        });
        when(users.selectById(viewer.getId())).thenReturn(viewer);
        when(users.selectById(owner.getId())).thenReturn(owner);

        service.createComment(viewer, 100L,
                new M6Dtos.CreateCommentRequest("comment", "请检查杯口高光", null, null, null, List.of("10")),
                new MockHttpServletRequest());

        verify(notifications).insert(org.mockito.ArgumentMatchers.<Notification>argThat(notification ->
                Objects.equals(notification.getUserId(), owner.getId())
                        && "picture_comment_created".equals(notification.getType())
                        && notification.getPayload().contains("\"rootId\":\"53\"")
                        && notification.getPayload().contains("\"commentId\":\"53\"")
                        && notification.getPayload().contains("\"kind\":\"comment\"")
                        && notification.getPayload().contains("请检查杯口高光")));
        verify(notifications, times(1)).insert(any(Notification.class));
        verify(mentions).insert(org.mockito.ArgumentMatchers.<CommentMention>argThat(mention ->
                Objects.equals(mention.getUserId(), owner.getId())));
    }

    @Test
    void commentThreadLoadsACompleteVisibleThread() {
        PictureComment root = new PictureComment();
        root.setId(50L); root.setPictureId(100L); root.setAuthorId(owner.getId());
        root.setKind("annotation"); root.setBody("杯把位置"); root.setStatus("active");
        PictureComment reply = new PictureComment();
        reply.setId(51L); reply.setPictureId(100L); reply.setRootId(50L); reply.setReplyToId(50L);
        reply.setAuthorId(owner.getId()); reply.setKind("reply"); reply.setBody("收到"); reply.setStatus("active");
        when(comments.selectById(50L)).thenReturn(root);
        when(comments.selectList(any())).thenReturn(List.of(reply));
        when(comments.selectCount(any())).thenReturn(1L);
        when(users.selectById(owner.getId())).thenReturn(owner);

        M6Dtos.CommentView result = service.commentThread(owner, 50L, new MockHttpServletRequest());

        assertEquals("50", result.id());
        assertEquals(1, result.replies().size());
        assertEquals("51", result.replies().get(0).id());
    }

    @Test
    void commentThreadHidesMissingAndInaccessibleThreads() {
        assertEquals(40400, assertThrows(V1Exception.class,
                () -> service.commentThread(owner, 404L, new MockHttpServletRequest())).getCode());

        PictureComment root = new PictureComment();
        root.setId(50L); root.setPictureId(100L); root.setAuthorId(owner.getId()); root.setKind("comment");
        when(comments.selectById(50L)).thenReturn(root);
        User stranger = new User(); stranger.setId(99L);

        assertEquals(40400, assertThrows(V1Exception.class,
                () -> service.commentThread(stranger, 50L, new MockHttpServletRequest())).getCode());
        verify(comments, never()).selectList(any());
    }

    @Test
    void replyCanTargetAnotherReplyInTheSameThread() {
        PictureComment root = new PictureComment();
        root.setId(50L); root.setPictureId(100L); root.setPictureVersionId(300L); root.setAuthorId(20L); root.setKind("comment");
        PictureComment target = new PictureComment();
        target.setId(51L); target.setPictureId(100L); target.setRootId(50L); target.setAuthorId(30L);
        when(comments.selectById(50L)).thenReturn(root);
        when(comments.selectById(51L)).thenReturn(target);
        when(comments.insert(any(PictureComment.class))).thenAnswer(invocation -> {
            ((PictureComment) invocation.getArgument(0)).setId(52L);
            return 1;
        });
        when(users.selectById(owner.getId())).thenReturn(owner);

        M6Dtos.CommentView result = service.reply(owner, 50L,
                new M6Dtos.CreateReplyRequest("补充回复", "51", List.of()),
                new MockHttpServletRequest());

        assertEquals("51", result.replyToId());
        verify(comments).insert(org.mockito.ArgumentMatchers.<PictureComment>argThat(row ->
                Objects.equals(row.getRootId(), 50L) && Objects.equals(row.getReplyToId(), 51L)));
        verify(notifications).insert(org.mockito.ArgumentMatchers.<Notification>argThat(notification ->
                Objects.equals(notification.getUserId(), 30L)
                        && notification.getPayload().contains("\"rootId\":\"50\"")
                        && notification.getPayload().contains("\"commentId\":\"52\"")));
    }
}
