"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { teamApi } from "./api";

const keys = { root: ["team"] as const, spaces: ["team", "spaces"] as const, space: (id: string) => ["team", "space", id] as const, members: (id: string) => ["team", "members", id] as const, pictures: (id: string) => ["team", "pictures", id] as const, invitations: ["team", "invitations"] as const, notifications: ["team", "notifications"] as const };
export function useTeamSpaces() { return useQuery({ queryKey: keys.spaces, queryFn: teamApi.listSpaces }); }
export function useTeamSpace(id: string) { return useQuery({ queryKey: keys.space(id), queryFn: () => teamApi.getSpace(id), enabled: Boolean(id) }); }
export function useTeamMembers(id: string) { return useQuery({ queryKey: keys.members(id), queryFn: () => teamApi.listMembers(id), enabled: Boolean(id) }); }
export function useTeamPictures(id: string) { return useQuery({ queryKey: keys.pictures(id), queryFn: () => teamApi.listPictures(id), enabled: Boolean(id) }); }
export function useInvitations() { return useQuery({ queryKey: keys.invitations, queryFn: teamApi.listInvitations }); }
export function useNotifications(enabled = true) { return useQuery({ queryKey: keys.notifications, queryFn: teamApi.listNotifications, enabled, refetchInterval: 30_000, refetchIntervalInBackground: false, refetchOnWindowFocus: true }); }
function useRefresh() { const client = useQueryClient(); return () => client.invalidateQueries({ queryKey: keys.root }); }
export function useCreateTeamSpace() { const refresh = useRefresh(); return useMutation({ mutationFn: teamApi.createSpace, onSuccess: refresh }); }
export function useInviteMember() { const refresh = useRefresh(); return useMutation({ mutationFn: ({ spaceId, inviteeId, role }: { spaceId: string; inviteeId: string; role: "viewer" | "editor" }) => teamApi.invite(spaceId, inviteeId, role), onSuccess: refresh }); }
export function useUpdateMember() { const refresh = useRefresh(); return useMutation({ mutationFn: ({ spaceId, memberId, role }: { spaceId: string; memberId: string; role: "admin" | "editor" | "viewer" }) => teamApi.updateMember(spaceId, memberId, role), onSuccess: refresh }); }
export function useRemoveMember() { const refresh = useRefresh(); return useMutation({ mutationFn: ({ spaceId, memberId }: { spaceId: string; memberId: string }) => teamApi.removeMember(spaceId, memberId), onSuccess: refresh }); }
export function useRespondInvitation() { const refresh = useRefresh(); return useMutation({ mutationFn: ({ id, accept }: { id: string; accept: boolean }) => accept ? teamApi.acceptInvitation(id) : teamApi.rejectInvitation(id), onSuccess: refresh }); }
export function useMarkNotificationsRead() { const refresh = useRefresh(); return useMutation({ mutationFn: teamApi.markNotificationsRead, onSuccess: refresh }); }
export function useDeleteTeamSpace() { const refresh = useRefresh(); return useMutation({ mutationFn: ({ spaceId, name }: { spaceId: string; name: string }) => teamApi.deleteSpace(spaceId, name), onSuccess: refresh }); }
export function useTransferOwnership() { const refresh = useRefresh(); return useMutation({ mutationFn: ({ spaceId, memberId }: { spaceId: string; memberId: string }) => teamApi.transferOwnership(spaceId, memberId), onSuccess: refresh }); }
