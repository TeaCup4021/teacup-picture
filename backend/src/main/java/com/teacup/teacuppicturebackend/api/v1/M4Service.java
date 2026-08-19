package com.teacup.teacuppicturebackend.api.v1;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.api.v1.model.M4Dtos;
import com.teacup.teacuppicturebackend.mapper.*;
import com.teacup.teacuppicturebackend.model.entity.*;
import com.teacup.teacuppicturebackend.model.enums.SpaceLevelEnum;
import com.teacup.teacuppicturebackend.model.enums.SpaceTypeEnum;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureStorageDeleteService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class M4Service {
    private static final int TEAM_LIMIT = 5;
    private static final long INVITATION_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private final SpaceMapper spaces;
    private final SpaceUserMapper members;
    private final SpaceInvitationMapper invitations;
    private final NotificationMapper notifications;
    private final PictureMapper pictures;
    private final PictureVersionMapper versions;
    private final UserMapper users;
    private final UserService userService;
    private final SpaceAccessService access;
    private final PictureStorageDeleteService deletions;
    private final ObjectMapper json;

    public M4Service(SpaceMapper spaces, SpaceUserMapper members, SpaceInvitationMapper invitations,
                     NotificationMapper notifications, PictureMapper pictures, PictureVersionMapper versions,
                     UserMapper users, UserService userService, SpaceAccessService access,
                     PictureStorageDeleteService deletions, ObjectMapper json) {
        this.spaces = spaces; this.members = members; this.invitations = invitations;
        this.notifications = notifications; this.pictures = pictures; this.versions = versions;
        this.users = users; this.userService = userService; this.access = access; this.deletions = deletions; this.json = json;
    }

    @Transactional(rollbackFor = Exception.class)
    public M4Dtos.SpaceView create(User user, M4Dtos.CreateSpaceRequest request) {
        String name = requiredName(request == null ? null : request.name());
        if (users.lockActiveById(user.getId()) == null) throw V1Exception.unauthorized();
        long owned = spaces.selectCount(new LambdaQueryWrapper<Space>().eq(Space::getOwnerId, user.getId())
                .eq(Space::getSpaceType, SpaceTypeEnum.TEAM.getValue()).eq(Space::getIsDelete, 0));
        if (owned >= TEAM_LIMIT) throw V1Exception.conflict("普通用户最多拥有 5 个团队空间");
        Space space = new Space();
        space.setSpaceName(name); space.setSpaceType(SpaceTypeEnum.TEAM.getValue());
        space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue()); space.setMaxSize(SpaceLevelEnum.COMMON.getMaxSize());
        space.setMaxCount(SpaceLevelEnum.COMMON.getMaxCount()); space.setTotalSize(0L); space.setTotalCount(0L);
        space.setUserId(user.getId()); space.setOwnerId(user.getId()); space.setIsDelete(0);
        if (spaces.insert(space) != 1) throw V1Exception.conflict("创建团队空间失败");
        SpaceUser owner = new SpaceUser(); owner.setSpaceId(space.getId()); owner.setUserId(user.getId()); owner.setSpaceRole(SpaceAccessService.OWNER);
        if (members.insert(owner) != 1) throw V1Exception.conflict("创建团队成员失败");
        return spaceView(user, space, SpaceAccessService.OWNER);
    }

    public M4Dtos.SpaceList list(User user) {
        List<SpaceUser> rows = members.selectList(new LambdaQueryWrapper<SpaceUser>().eq(SpaceUser::getUserId, user.getId()));
        if (rows.isEmpty()) return new M4Dtos.SpaceList(List.of());
        Map<Long, String> roles = rows.stream().collect(Collectors.toMap(SpaceUser::getSpaceId, SpaceUser::getSpaceRole, (a, b) -> a));
        List<Space> teamSpaces = spaces.selectBatchIds(roles.keySet()).stream()
                .filter(s -> Integer.valueOf(SpaceTypeEnum.TEAM.getValue()).equals(s.getSpaceType()) && !Integer.valueOf(1).equals(s.getIsDelete())).toList();
        return new M4Dtos.SpaceList(teamSpaces.stream().map(s -> spaceView(user, s, roles.get(s.getId()))).toList());
    }

    public M4Dtos.SpaceView get(User user, long spaceId) { Space space = teamSpace(spaceId); return spaceView(user, access.requireVisible(user, spaceId), access.roleOf(user, space)); }

    @Transactional(rollbackFor = Exception.class)
    public M4Dtos.SpaceView update(User user, long spaceId, M4Dtos.UpdateSpaceRequest request) {
        Space space = teamSpace(spaceId); access.requireRole(user, space, SpaceAccessService.ADMIN);
        space.setSpaceName(requiredName(request == null ? null : request.name())); spaces.updateById(space);
        return spaceView(user, space, access.roleOf(user, space));
    }

    public M4Dtos.MemberList listMembers(User user, long spaceId) {
        Space space = teamSpace(spaceId); access.requireVisible(user, spaceId);
        return new M4Dtos.MemberList(memberRows(space));
    }

    public M4Dtos.UserSearchPage searchUsers(User user, long spaceId, String q) {
        Space space = teamSpace(spaceId); access.requireRole(user, space, SpaceAccessService.ADMIN);
        if (q == null || q.trim().length() < 2 || q.trim().length() > 64) throw V1Exception.badRequest("搜索关键词长度必须为 2 到 64");
        String term = q.trim();
        List<User> found = users.selectList(new LambdaQueryWrapper<User>().eq(User::getIsDelete, 0)
                .and(w -> w.eq(User::getUserAccount, term).or().like(User::getUserName, term)).last("LIMIT 20"));
        Set<Long> memberIds = members.selectList(new LambdaQueryWrapper<SpaceUser>().eq(SpaceUser::getSpaceId, spaceId)).stream().map(SpaceUser::getUserId).collect(Collectors.toSet());
        Set<Long> pending = invitations.selectList(new LambdaQueryWrapper<SpaceInvitation>().eq(SpaceInvitation::getSpaceId, spaceId).eq(SpaceInvitation::getStatus, "pending")).stream().map(SpaceInvitation::getInviteeId).collect(Collectors.toSet());
        return new M4Dtos.UserSearchPage(found.stream().filter(u -> !Objects.equals(u.getId(), user.getId())).map(u ->
                new M4Dtos.UserSearchResult(id(u.getId()), u.getUserName(), u.getUserAvatar(), mask(u.getUserAccount()), memberIds.contains(u.getId()) ? "member" : pending.contains(u.getId()) ? "pending" : "none")).toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public M4Dtos.InvitationView invite(User user, long spaceId, M4Dtos.CreateInvitationRequest request) {
        Space space = teamSpace(spaceId); access.requireRole(user, space, SpaceAccessService.ADMIN);
        if (request == null || !SpaceAccessService.isInvitableRole(request.role())) throw V1Exception.badRequest("邀请角色只能是 viewer 或 editor");
        long inviteeId = parseId(request.inviteeId());
        if (Objects.equals(inviteeId, user.getId())) throw V1Exception.badRequest("不能邀请自己");
        User invitee = activeUser(inviteeId);
        if (members.selectCount(new LambdaQueryWrapper<SpaceUser>().eq(SpaceUser::getSpaceId, spaceId).eq(SpaceUser::getUserId, inviteeId)) > 0) throw V1Exception.conflict("用户已经是团队成员");
        expireInvitations(spaceId, inviteeId);
        SpaceInvitation invitation = new SpaceInvitation(); invitation.setSpaceId(spaceId); invitation.setInviterId(user.getId()); invitation.setInviteeId(inviteeId);
        invitation.setSpaceRole(request.role()); invitation.setStatus("pending"); invitation.setExpiresAt(new Date(System.currentTimeMillis() + INVITATION_TTL_MS));
        try { invitations.insert(invitation); } catch (DuplicateKeyException e) { throw V1Exception.conflict("用户已有待处理邀请"); }
        notify(inviteeId, "space_invitation", user.getId(), "space_invitation", invitation.getId(), Map.of("spaceId", id(spaceId), "spaceName", space.getSpaceName(), "role", request.role()));
        return invitationView(invitation);
    }

    public M4Dtos.InvitationPage invitations(User user, int page, int pageSize, String status) {
        validatePage(page, pageSize); expireInvitationsForUser(user.getId());
        LambdaQueryWrapper<SpaceInvitation> query = new LambdaQueryWrapper<SpaceInvitation>().eq(SpaceInvitation::getInviteeId, user.getId()).orderByDesc(SpaceInvitation::getCreateTime).orderByDesc(SpaceInvitation::getId);
        if (status != null && !status.isBlank()) query.eq(SpaceInvitation::getStatus, status);
        Page<SpaceInvitation> result = invitations.selectPage(new Page<>(page, pageSize), query);
        return new M4Dtos.InvitationPage(result.getRecords().stream().map(this::invitationView).toList(), pageMeta(result));
    }

    @Transactional(rollbackFor = Exception.class)
    public M4Dtos.InvitationView accept(User user, long invitationId) { return respond(user, invitationId, true); }
    @Transactional(rollbackFor = Exception.class)
    public M4Dtos.InvitationView reject(User user, long invitationId) { return respond(user, invitationId, false); }

    @Transactional(rollbackFor = Exception.class)
    public M4Dtos.MemberView updateMember(User user, long spaceId, long memberId, M4Dtos.UpdateMemberRequest request) {
        Space space = teamSpace(spaceId); access.requireRole(user, space, SpaceAccessService.ADMIN);
        if (request == null || !SpaceAccessService.isAssignableRole(request.role())) throw V1Exception.badRequest("成员角色无效");
        SpaceUser member = member(spaceId, memberId);
        if (SpaceAccessService.OWNER.equals(member.getSpaceRole())) throw V1Exception.conflict("所有者角色不能直接修改");
        member.setSpaceRole(request.role()); members.updateById(member);
        notify(member.getUserId(), "space_member_role_changed", user.getId(), "space", spaceId, Map.of("spaceId", id(spaceId), "spaceName", space.getSpaceName(), "role", request.role()));
        return memberView(member, activeUser(member.getUserId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeMember(User user, long spaceId, long memberId) {
        Space space = teamSpace(spaceId); access.requireRole(user, space, SpaceAccessService.ADMIN);
        SpaceUser member = member(spaceId, memberId);
        if (SpaceAccessService.OWNER.equals(member.getSpaceRole())) throw V1Exception.conflict("必须先转让所有权");
        members.deleteById(memberId);
        notify(member.getUserId(), "space_member_removed", user.getId(), "space", spaceId, Map.of("spaceId", id(spaceId), "spaceName", space.getSpaceName()));
    }

    @Transactional(rollbackFor = Exception.class)
    public M4Dtos.SpaceView transferOwnership(User user, long spaceId, M4Dtos.TransferOwnershipRequest request) {
        Space space = teamSpace(spaceId); if (!access.isOwner(user, space)) throw V1Exception.forbidden();
        SpaceUser target = member(spaceId, parseId(request == null ? null : request.memberId()));
        if (Objects.equals(target.getUserId(), user.getId())) throw V1Exception.badRequest("目标成员已经是所有者");
        SpaceUser oldOwner = members.selectOne(new LambdaQueryWrapper<SpaceUser>().eq(SpaceUser::getSpaceId, spaceId).eq(SpaceUser::getUserId, user.getId()));
        if (oldOwner == null) throw V1Exception.conflict("所有者成员关系缺失");
        space.setOwnerId(target.getUserId()); spaces.updateById(space);
        oldOwner.setSpaceRole(SpaceAccessService.ADMIN); target.setSpaceRole(SpaceAccessService.OWNER);
        members.updateById(oldOwner); members.updateById(target);
        notify(target.getUserId(), "space_ownership_transferred", user.getId(), "space", spaceId, Map.of("spaceId", id(spaceId), "spaceName", space.getSpaceName()));
        return spaceView(targetUser(user, target.getUserId()), space, SpaceAccessService.OWNER);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(User user, long spaceId, M4Dtos.DeleteSpaceRequest request) {
        if (spaces.lockActiveById(spaceId) == null) throw V1Exception.notFound();
        Space space = teamSpace(spaceId); if (!access.isOwner(user, space)) throw V1Exception.forbidden();
        if (request == null || !Objects.equals(space.getSpaceName(), request.confirmationName())) throw V1Exception.badRequest("确认名称不匹配");
        List<Picture> pictureRows = pictures.selectList(new LambdaQueryWrapper<Picture>().eq(Picture::getSpaceId, spaceId));
        for (Picture picture : pictureRows) {
            deletions.enqueueAssets(picture.getId(), picture.getObjectKey(), picture.getThumbnailObjectKey());
            for (PictureVersion version : versions.selectList(new LambdaQueryWrapper<PictureVersion>().eq(PictureVersion::getPictureId, picture.getId()))) {
                deletions.enqueueAssets(picture.getId(), version.getAssetObjectKey(), version.getThumbnailObjectKey());
            }
        }
        invitations.delete(new LambdaQueryWrapper<SpaceInvitation>().eq(SpaceInvitation::getSpaceId, spaceId));
        notifications.delete(new LambdaQueryWrapper<Notification>().eq(Notification::getResourceId, spaceId)
                .in(Notification::getResourceType, "space", "space_invitation"));
        pictures.purgeBySpaceId(spaceId); members.deleteBySpaceId(spaceId);
        if (spaces.purgeById(spaceId) != 1) throw V1Exception.conflict("删除团队空间失败");
    }

    public M4Dtos.NotificationPage notifications(User user, int page, int pageSize) {
        validatePage(page, pageSize);
        Page<Notification> result = notifications.selectPage(new Page<>(page, pageSize), new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, user.getId()).orderByDesc(Notification::getCreateTime).orderByDesc(Notification::getId));
        long unread = notifications.selectCount(new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, user.getId()).isNull(Notification::getReadAt));
        return new M4Dtos.NotificationPage(result.getRecords().stream().map(this::notificationView).toList(), pageMeta(result), unread);
    }

    @Transactional(rollbackFor = Exception.class)
    public long markNotificationsRead(User user, M4Dtos.MarkNotificationsReadRequest request) {
        if (request == null) throw V1Exception.badRequest("请求不能为空");
        LambdaQueryWrapper<Notification> query = new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, user.getId()).isNull(Notification::getReadAt);
        if (!request.all()) {
            if (request.notificationIds() == null || request.notificationIds().isEmpty()) throw V1Exception.badRequest("通知不能为空");
            query.in(Notification::getId, request.notificationIds().stream().map(this::parseId).toList());
        }
        Notification update = new Notification(); update.setReadAt(new Date());
        notifications.update(update, query);
        return notifications.selectCount(new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, user.getId()).isNull(Notification::getReadAt));
    }

    private M4Dtos.InvitationView respond(User user, long invitationId, boolean accept) {
        SpaceInvitation invitation = invitations.selectById(invitationId);
        if (invitation == null || !Objects.equals(invitation.getInviteeId(), user.getId())) throw V1Exception.notFound();
        if (!"pending".equals(invitation.getStatus())) throw V1Exception.conflict("邀请已处理");
        if (invitation.getExpiresAt().before(new Date())) { invitation.setStatus("expired"); invitations.updateById(invitation); throw V1Exception.conflict("邀请已过期"); }
        Space space = teamSpace(invitation.getSpaceId());
        if (accept) {
            if (members.selectCount(new LambdaQueryWrapper<SpaceUser>().eq(SpaceUser::getSpaceId, space.getId()).eq(SpaceUser::getUserId, user.getId())) > 0) throw V1Exception.conflict("已加入团队");
            SpaceUser member = new SpaceUser(); member.setSpaceId(space.getId()); member.setUserId(user.getId()); member.setSpaceRole(invitation.getSpaceRole()); members.insert(member);
            invitation.setStatus("accepted");
        } else invitation.setStatus("rejected");
        invitation.setRespondedAt(new Date()); invitations.updateById(invitation);
        notify(invitation.getInviterId(), accept ? "space_invitation_accepted" : "space_invitation_rejected", user.getId(), "space_invitation", invitation.getId(), Map.of("spaceId", id(space.getId()), "spaceName", space.getSpaceName()));
        return invitationView(invitation);
    }

    private void expireInvitations(long spaceId, long inviteeId) { expire(new LambdaQueryWrapper<SpaceInvitation>().eq(SpaceInvitation::getSpaceId, spaceId).eq(SpaceInvitation::getInviteeId, inviteeId)); }
    private void expireInvitationsForUser(long userId) { expire(new LambdaQueryWrapper<SpaceInvitation>().eq(SpaceInvitation::getInviteeId, userId)); }
    private void expire(LambdaQueryWrapper<SpaceInvitation> query) { SpaceInvitation update = new SpaceInvitation(); update.setStatus("expired"); update.setRespondedAt(new Date()); invitations.update(update, query.eq(SpaceInvitation::getStatus, "pending").lt(SpaceInvitation::getExpiresAt, new Date())); }
    private Space teamSpace(long id) { Space space = spaces.selectById(id); if (space == null || Integer.valueOf(1).equals(space.getIsDelete()) || !Integer.valueOf(SpaceTypeEnum.TEAM.getValue()).equals(space.getSpaceType())) throw V1Exception.notFound(); return space; }
    private SpaceUser member(long spaceId, long memberId) { SpaceUser value = members.selectById(memberId); if (value == null || !Objects.equals(value.getSpaceId(), spaceId)) throw V1Exception.notFound(); return value; }
    private User activeUser(long id) { User user = users.selectById(id); if (user == null || Integer.valueOf(1).equals(user.getIsDelete())) throw V1Exception.notFound(); return user; }
    private User targetUser(User fallback, long id) { return Objects.equals(fallback.getId(), id) ? fallback : activeUser(id); }
    private List<M4Dtos.MemberView> memberRows(Space space) { return members.selectList(new LambdaQueryWrapper<SpaceUser>().eq(SpaceUser::getSpaceId, space.getId()).orderByAsc(SpaceUser::getCreateTime)).stream().map(m -> memberView(m, activeUser(m.getUserId()))).toList(); }
    private M4Dtos.MemberView memberView(SpaceUser member, User user) { return new M4Dtos.MemberView(id(member.getId()), id(member.getUserId()), user.getUserName(), user.getUserAvatar(), mask(user.getUserAccount()), member.getSpaceRole(), instant(member.getCreateTime())); }
    private M4Dtos.SpaceView spaceView(User user, Space space, String role) { int count = Math.toIntExact(members.selectCount(new LambdaQueryWrapper<SpaceUser>().eq(SpaceUser::getSpaceId, space.getId()))); return new M4Dtos.SpaceView(id(space.getId()), space.getSpaceName(), "team", role, id(space.getOwnerId()), nz(space.getMaxSize()), nz(space.getMaxCount()), nz(space.getTotalSize()), nz(space.getTotalCount()), count, access.permissions(user, space), instant(space.getCreateTime()), instant(space.getUpdateTime())); }
    private M4Dtos.InvitationView invitationView(SpaceInvitation invitation) { Space space = teamSpace(invitation.getSpaceId()); SpaceUser inviterMember = members.selectOne(new LambdaQueryWrapper<SpaceUser>().eq(SpaceUser::getSpaceId, space.getId()).eq(SpaceUser::getUserId, invitation.getInviterId())); User inviter = activeUser(invitation.getInviterId()); M4Dtos.MemberView inviterView = new M4Dtos.MemberView(inviterMember == null ? null : id(inviterMember.getId()), id(inviter.getId()), inviter.getUserName(), inviter.getUserAvatar(), mask(inviter.getUserAccount()), inviterMember == null ? null : inviterMember.getSpaceRole(), inviterMember == null ? null : instant(inviterMember.getCreateTime())); return new M4Dtos.InvitationView(id(invitation.getId()), spaceView(null, space, null), inviterView, invitation.getSpaceRole(), invitation.getStatus(), instant(invitation.getExpiresAt()), instant(invitation.getCreateTime()), instant(invitation.getRespondedAt())); }
    private M4Dtos.NotificationView notificationView(Notification item) { User actor = item.getActorId() == null ? null : users.selectById(item.getActorId()); return new M4Dtos.NotificationView(id(item.getId()), item.getType(), actor == null ? null : new M4Dtos.MemberView(null, id(actor.getId()), actor.getUserName(), actor.getUserAvatar(), mask(actor.getUserAccount()), null, null), item.getResourceType(), id(item.getResourceId()), payload(item.getPayload()), instant(item.getReadAt()), instant(item.getCreateTime())); }
    private void notify(long recipientId, String type, long actorId, String resourceType, Long resourceId, Map<String, Object> payload) { Notification row = new Notification(); row.setUserId(recipientId); row.setType(type); row.setActorId(actorId); row.setResourceType(resourceType); row.setResourceId(resourceId); try { row.setPayload(json.writeValueAsString(payload)); } catch (Exception e) { throw new IllegalStateException("通知序列化失败", e); } notifications.insert(row); }
    private Map<String, Object> payload(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception e) { return Map.of(); } }
    private M1Dtos.PageMeta pageMeta(Page<?> page) { return new M1Dtos.PageMeta((int) page.getCurrent(), (int) page.getSize(), page.getTotal(), page.getPages()); }
    private void validatePage(int page, int size) { if (page < 1 || size < 1 || size > 100) throw V1Exception.badRequest("分页参数无效"); }
    private String requiredName(String value) { if (value == null || value.trim().isEmpty() || value.trim().length() > 30) throw V1Exception.badRequest("团队名称长度必须为 1 到 30"); return value.trim(); }
    private long parseId(String value) { try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; } catch (RuntimeException e) { throw V1Exception.badRequest("ID 格式无效"); } }
    private static String id(Long value) { return value == null ? null : value.toString(); }
    private static long nz(Long value) { return value == null ? 0L : value; }
    private static Instant instant(Date value) { return value == null ? null : value.toInstant(); }
    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private static String mask(String account) { if (account == null || account.length() < 3) return "***"; return account.substring(0, 2) + "***" + account.substring(account.length() - 1); }
}
