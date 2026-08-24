package com.teacup.teacuppicturebackend.api.v1;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teacup.teacuppicturebackend.mapper.SpaceMapper;
import com.teacup.teacuppicturebackend.mapper.SpaceUserMapper;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.entity.SpaceUser;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.model.enums.SpaceTypeEnum;
import com.teacup.teacuppicturebackend.service.UserService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class SpaceAccessService {
    public static final String OWNER = "owner";
    public static final String ADMIN = "admin";
    public static final String EDITOR = "editor";
    public static final String VIEWER = "viewer";

    private final SpaceMapper spaces;
    private final SpaceUserMapper members;
    private final UserService users;

    public SpaceAccessService(SpaceMapper spaces, SpaceUserMapper members, UserService users) {
        this.spaces = spaces;
        this.members = members;
        this.users = users;
    }

    public Space requireVisible(User user, long spaceId) {
        Space space = spaces.selectById(spaceId);
        if (space == null || Integer.valueOf(1).equals(space.getIsDelete()) || !canView(user, space)) {
            throw V1Exception.notFound();
        }
        return space;
    }

    public String requireRole(User user, Space space, String requiredRole) {
        String role = roleOf(user, space);
        if (!hasRole(role, requiredRole)) throw V1Exception.forbidden();
        return role;
    }

    public String roleOf(User user, Space space) {
        if (user == null || space == null) return null;
        if (users.isAdmin(user)) return OWNER;
        Long ownerId = space.getOwnerId() == null ? space.getUserId() : space.getOwnerId();
        if (Objects.equals(ownerId, user.getId())) return OWNER;
        if (Integer.valueOf(SpaceTypeEnum.PRIVATE.getValue()).equals(space.getSpaceType())) return null;
        SpaceUser member = members.selectOne(new LambdaQueryWrapper<SpaceUser>()
                .eq(SpaceUser::getSpaceId, space.getId()).eq(SpaceUser::getUserId, user.getId()));
        return member == null ? null : member.getSpaceRole();
    }

    public boolean canView(User user, Space space) { return roleOf(user, space) != null; }
    public boolean canUpload(User user, Space space) { return hasRole(roleOf(user, space), EDITOR); }
    public boolean canEdit(User user, Space space) { return hasRole(roleOf(user, space), EDITOR); }
    public boolean canManageMembers(User user, Space space) { return hasRole(roleOf(user, space), ADMIN); }
    public boolean isOwner(User user, Space space) {
        return user != null && space != null && Objects.equals(space.getOwnerId(), user.getId());
    }

    public List<String> permissions(User user, Space space) {
        String role = roleOf(user, space);
        if (role == null) return List.of();
        if (OWNER.equals(role) || ADMIN.equals(role)) return List.of("picture:view", "picture:upload", "picture:edit", "picture:delete", "picture:publish", "picture:share", "space:manage", "space:members");
        if (EDITOR.equals(role)) return List.of("picture:view", "picture:upload", "picture:edit", "picture:delete", "picture:publish");
        return List.of("picture:view");
    }

    public static boolean isAssignableRole(String value) { return VIEWER.equals(value) || EDITOR.equals(value) || ADMIN.equals(value); }
    public static boolean isInvitableRole(String value) { return VIEWER.equals(value) || EDITOR.equals(value); }
    private boolean hasRole(String actual, String required) {
        if (actual == null) return false;
        if (OWNER.equals(actual)) return true;
        if (ADMIN.equals(actual)) return !OWNER.equals(required);
        if (EDITOR.equals(actual)) return EDITOR.equals(required) || VIEWER.equals(required);
        return VIEWER.equals(required) && VIEWER.equals(actual);
    }
}
