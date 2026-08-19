package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.api.v1.model.M4Dtos;
import com.teacup.teacuppicturebackend.model.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1")
public class M4Controller {
    private final M1Service auth; private final M4Service service;
    public M4Controller(M1Service auth, M4Service service) { this.auth = auth; this.service = service; }
    @GetMapping("/spaces") public ResponseEntity<V1Response<M4Dtos.SpaceList>> list(HttpServletRequest r) { return response(HttpStatus.OK, service.list(auth.requireUser(r)), r); }
    @PostMapping("/spaces") public ResponseEntity<V1Response<M4Dtos.SpaceView>> create(@RequestBody M4Dtos.CreateSpaceRequest b, HttpServletRequest r) { return response(HttpStatus.CREATED, service.create(auth.requireUser(r), b), r); }
    @GetMapping("/spaces/{spaceId}") public ResponseEntity<V1Response<M4Dtos.SpaceView>> get(@PathVariable String spaceId, HttpServletRequest r) { return response(HttpStatus.OK, service.get(auth.requireUser(r), id(spaceId)), r); }
    @PatchMapping("/spaces/{spaceId}") public ResponseEntity<V1Response<M4Dtos.SpaceView>> update(@PathVariable String spaceId, @RequestBody M4Dtos.UpdateSpaceRequest b, HttpServletRequest r) { return response(HttpStatus.OK, service.update(auth.requireUser(r), id(spaceId), b), r); }
    @DeleteMapping("/spaces/{spaceId}") public ResponseEntity<V1Response<Void>> delete(@PathVariable String spaceId, @RequestBody M4Dtos.DeleteSpaceRequest b, HttpServletRequest r) { service.delete(auth.requireUser(r), id(spaceId), b); return response(HttpStatus.OK, null, r); }
    @GetMapping("/spaces/{spaceId}/members") public ResponseEntity<V1Response<M4Dtos.MemberList>> members(@PathVariable String spaceId, HttpServletRequest r) { return response(HttpStatus.OK, service.listMembers(auth.requireUser(r), id(spaceId)), r); }
    @PatchMapping("/spaces/{spaceId}/members/{memberId}") public ResponseEntity<V1Response<M4Dtos.MemberView>> updateMember(@PathVariable String spaceId, @PathVariable String memberId, @RequestBody M4Dtos.UpdateMemberRequest b, HttpServletRequest r) { return response(HttpStatus.OK, service.updateMember(auth.requireUser(r), id(spaceId), id(memberId), b), r); }
    @DeleteMapping("/spaces/{spaceId}/members/{memberId}") public ResponseEntity<V1Response<Void>> removeMember(@PathVariable String spaceId, @PathVariable String memberId, HttpServletRequest r) { service.removeMember(auth.requireUser(r), id(spaceId), id(memberId)); return response(HttpStatus.OK, null, r); }
    @PostMapping("/spaces/{spaceId}/transfer-ownership") public ResponseEntity<V1Response<M4Dtos.SpaceView>> transfer(@PathVariable String spaceId, @RequestBody M4Dtos.TransferOwnershipRequest b, HttpServletRequest r) { return response(HttpStatus.OK, service.transferOwnership(auth.requireUser(r), id(spaceId), b), r); }
    @GetMapping("/users/search") public ResponseEntity<V1Response<M4Dtos.UserSearchPage>> search(@RequestParam String q, @RequestParam String spaceId, HttpServletRequest r) { return response(HttpStatus.OK, service.searchUsers(auth.requireUser(r), id(spaceId), q), r); }
    @PostMapping("/spaces/{spaceId}/invitations") public ResponseEntity<V1Response<M4Dtos.InvitationView>> invite(@PathVariable String spaceId, @RequestBody M4Dtos.CreateInvitationRequest b, HttpServletRequest r) { return response(HttpStatus.CREATED, service.invite(auth.requireUser(r), id(spaceId), b), r); }
    @GetMapping("/invitations/me") public ResponseEntity<V1Response<M4Dtos.InvitationPage>> invitations(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String status, HttpServletRequest r) { return response(HttpStatus.OK, service.invitations(auth.requireUser(r), page, pageSize, status), r); }
    @PostMapping("/invitations/{invitationId}/accept") public ResponseEntity<V1Response<M4Dtos.InvitationView>> accept(@PathVariable String invitationId, HttpServletRequest r) { return response(HttpStatus.OK, service.accept(auth.requireUser(r), id(invitationId)), r); }
    @PostMapping("/invitations/{invitationId}/reject") public ResponseEntity<V1Response<M4Dtos.InvitationView>> reject(@PathVariable String invitationId, HttpServletRequest r) { return response(HttpStatus.OK, service.reject(auth.requireUser(r), id(invitationId)), r); }
    @GetMapping("/notifications") public ResponseEntity<V1Response<M4Dtos.NotificationPage>> notifications(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, HttpServletRequest r) { return response(HttpStatus.OK, service.notifications(auth.requireUser(r), page, pageSize), r); }
    @PostMapping("/notifications/read") public ResponseEntity<V1Response<Long>> read(@RequestBody M4Dtos.MarkNotificationsReadRequest b, HttpServletRequest r) { return response(HttpStatus.OK, service.markNotificationsRead(auth.requireUser(r), b), r); }
    private static long id(String value) { try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; } catch (RuntimeException e) { throw V1Exception.badRequest("ID 格式无效"); } }
    private static <T> ResponseEntity<V1Response<T>> response(HttpStatus status, T data, HttpServletRequest request) { return ResponseEntity.status(status).body(V1Response.success(data, RequestIdFilter.get(request))); }
}
