package com.teacup.teacuppicturebackend.api.v1;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.teacup.teacuppicturebackend.api.v1.model.M4Dtos;
import com.teacup.teacuppicturebackend.mapper.*;
import com.teacup.teacuppicturebackend.model.entity.*;
import com.teacup.teacuppicturebackend.model.enums.SpaceTypeEnum;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureStorageDeleteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class M4ServiceTest {
    private final SpaceMapper spaces = mock(SpaceMapper.class);
    private final SpaceUserMapper members = mock(SpaceUserMapper.class);
    private final SpaceInvitationMapper invitations = mock(SpaceInvitationMapper.class);
    private final NotificationMapper notifications = mock(NotificationMapper.class);
    private final PictureMapper pictures = mock(PictureMapper.class);
    private final PictureVersionMapper versions = mock(PictureVersionMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final UserService userService = mock(UserService.class);
    private final SpaceAccessService access = mock(SpaceAccessService.class);
    private final PictureStorageDeleteService deletions = mock(PictureStorageDeleteService.class);
    private M4Service service;
    private User owner;
    private Space space;

    @BeforeEach
    void setUp() {
        service = new M4Service(spaces, members, invitations, notifications, pictures, versions, users,
                userService, access, deletions, new com.fasterxml.jackson.databind.ObjectMapper());
        owner = new User(); owner.setId(10L); owner.setUserName("Owner"); owner.setUserAccount("owner-account"); owner.setIsDelete(0);
        space = new Space(); space.setId(100L); space.setSpaceName("设计团队"); space.setSpaceType(SpaceTypeEnum.TEAM.getValue());
        space.setOwnerId(10L); space.setUserId(10L); space.setIsDelete(0); space.setMaxSize(1000L); space.setMaxCount(10L); space.setTotalSize(0L); space.setTotalCount(0L);
        when(spaces.selectById(100L)).thenReturn(space);
        when(users.selectById(10L)).thenReturn(owner);
        when(members.selectCount(any())).thenReturn(1L);
    }

    @Test
    void createLocksOwnerAndCreatesOwnerMembership() {
        when(users.lockActiveById(10L)).thenReturn(10L);
        when(spaces.selectCount(any())).thenReturn(0L);
        when(spaces.insert(any(Space.class))).thenAnswer(invocation -> { ((Space) invocation.getArgument(0)).setId(101L); return 1; });
        when(members.insert(any(SpaceUser.class))).thenReturn(1);

        M4Dtos.SpaceView result = service.create(owner, new M4Dtos.CreateSpaceRequest("新团队"));

        assertEquals("101", result.id());
        assertEquals("owner", result.role());
        verify(members).insert(org.mockito.ArgumentMatchers.<SpaceUser>argThat(member -> member.getSpaceId().equals(101L)
                && member.getUserId().equals(10L) && "owner".equals(member.getSpaceRole())));
    }

    @Test
    void createRejectsSixthOwnedTeamSpace() {
        when(users.lockActiveById(10L)).thenReturn(10L);
        when(spaces.selectCount(any())).thenReturn(5L);

        assertEquals(40901, assertThrows(V1Exception.class,
                () -> service.create(owner, new M4Dtos.CreateSpaceRequest("超过上限"))).getCode());
        verify(spaces, never()).insert(any(Space.class));
    }

    @Test
    void inviteCreatesPendingInvitationAndNotification() {
        User invitee = new User(); invitee.setId(20L); invitee.setUserName("Invitee"); invitee.setUserAccount("invitee-account"); invitee.setIsDelete(0);
        SpaceUser inviterMember = new SpaceUser(); inviterMember.setId(1L); inviterMember.setSpaceId(100L); inviterMember.setUserId(10L); inviterMember.setSpaceRole("owner");
        when(users.selectById(20L)).thenReturn(invitee);
        when(members.selectCount(any())).thenReturn(0L, 1L);
        when(members.selectList(any())).thenReturn(List.of(inviterMember));
        when(invitations.selectList(any())).thenReturn(List.of());
        when(invitations.insert(any(SpaceInvitation.class))).thenAnswer(invocation -> { ((SpaceInvitation) invocation.getArgument(0)).setId(30L); return 1; });
        when(access.requireRole(owner, space, SpaceAccessService.ADMIN)).thenReturn("owner");

        M4Dtos.InvitationView result = service.invite(owner, 100L, new M4Dtos.CreateInvitationRequest("20", "editor"));

        assertEquals("30", result.id());
        assertEquals("pending", result.status());
        verify(notifications).insert(org.mockito.ArgumentMatchers.<Notification>argThat(notification -> notification.getUserId().equals(20L) && "space_invitation".equals(notification.getType())));
    }

    @Test
    void acceptsInvitationCreatesMemberAndMarksAccepted() {
        User invitee = new User(); invitee.setId(20L); invitee.setUserName("Invitee"); invitee.setUserAccount("invitee-account"); invitee.setIsDelete(0);
        SpaceInvitation invitation = new SpaceInvitation(); invitation.setId(30L); invitation.setSpaceId(100L); invitation.setInviterId(10L); invitation.setInviteeId(20L); invitation.setSpaceRole("viewer"); invitation.setStatus("pending"); invitation.setExpiresAt(new Date(System.currentTimeMillis() + 60_000L));
        SpaceUser inviterMember = new SpaceUser(); inviterMember.setId(1L); inviterMember.setSpaceId(100L); inviterMember.setUserId(10L); inviterMember.setSpaceRole("owner");
        when(invitations.selectById(30L)).thenReturn(invitation);
        when(members.selectCount(any())).thenReturn(0L);
        when(members.selectOne(any())).thenReturn(inviterMember);
        when(members.insert(any(SpaceUser.class))).thenReturn(1);
        when(users.selectById(20L)).thenReturn(invitee);

        M4Dtos.InvitationView result = service.accept(invitee, 30L);

        assertEquals("accepted", result.status());
        verify(members).insert(org.mockito.ArgumentMatchers.<SpaceUser>argThat(member -> member.getUserId().equals(20L) && "viewer".equals(member.getSpaceRole())));
        verify(notifications).insert(org.mockito.ArgumentMatchers.<Notification>argThat(notification -> notification.getUserId().equals(10L) && "space_invitation_accepted".equals(notification.getType())));
    }

    @Test
    void deleteQueuesCurrentAndVersionAssetsBeforePurgingSpace() {
        Picture picture = new Picture(); picture.setId(200L); picture.setObjectKey("spaces/100/current.png"); picture.setThumbnailObjectKey("spaces/100/current-thumb.jpg");
        PictureVersion version = new PictureVersion(); version.setId(201L); version.setPictureId(200L); version.setAssetObjectKey("spaces/100/version.png"); version.setThumbnailObjectKey("spaces/100/version-thumb.jpg");
        when(spaces.lockActiveById(100L)).thenReturn(100L);
        when(access.isOwner(owner, space)).thenReturn(true);
        when(pictures.selectList(any())).thenReturn(List.of(picture));
        when(versions.selectList(any())).thenReturn(List.of(version));
        when(spaces.purgeById(100L)).thenReturn(1);

        service.delete(owner, 100L, new M4Dtos.DeleteSpaceRequest("设计团队"));

        verify(deletions).enqueueAssets(200L, "spaces/100/current.png", "spaces/100/current-thumb.jpg");
        verify(deletions).enqueueAssets(200L, "spaces/100/version.png", "spaces/100/version-thumb.jpg");
        verify(pictures).purgeBySpaceId(100L);
        verify(members).deleteBySpaceId(100L);
        verify(spaces).purgeById(100L);
    }
}
