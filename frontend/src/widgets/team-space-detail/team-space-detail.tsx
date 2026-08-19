"use client";

import { DeleteOutlined, EditOutlined, SearchOutlined, SwapOutlined, TeamOutlined, UploadOutlined, UserAddOutlined } from "@ant-design/icons";
import { App, Avatar, Button, Empty, Input, List, Modal, Popconfirm, Result, Select, Skeleton, Space, Tabs, Tag } from "antd";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { PictureTile } from "@/features/prototype/ui/picture-tile";
import { teamApi, type TeamMember, type TeamRole, type UserSearchResult, useDeleteTeamSpace, useInviteMember, useRemoveMember, useTeamMembers, useTeamPictures, useTeamSpace, useTransferOwnership, useUpdateMember } from "@/features/team";

const roleLabel: Record<TeamRole, string> = { owner: "所有者", admin: "管理员", editor: "编辑者", viewer: "浏览者" };

export function TeamSpaceDetail({ spaceId }: { spaceId: string }) {
  const { message } = App.useApp(); const router = useRouter();
  const space = useTeamSpace(spaceId); const members = useTeamMembers(spaceId); const pictures = useTeamPictures(spaceId);
  const updateMember = useUpdateMember(); const removeMember = useRemoveMember(); const transfer = useTransferOwnership(); const deletion = useDeleteTeamSpace();
  const invite = useInviteMember(); const [inviteOpen, setInviteOpen] = useState(false); const [query, setQuery] = useState(""); const [results, setResults] = useState<UserSearchResult[]>([]); const [searching, setSearching] = useState(false); const [inviteRole, setInviteRole] = useState<"viewer" | "editor">("viewer"); const [deleteName, setDeleteName] = useState("");
  if (space.isLoading) return <main className="content-shell"><Skeleton active paragraph={{ rows: 10 }} /></main>;
  if (space.isError || !space.data) return <Result status="404" title="团队空间不存在或你无权访问" />;
  const canManage = ["owner", "admin"].includes(space.data.role); const canEdit = ["owner", "admin", "editor"].includes(space.data.role);
  const search = async () => { if (query.trim().length < 2) return void message.warning("请输入至少 2 个字符"); setSearching(true); try { setResults((await teamApi.searchUsers(spaceId, query.trim())).items); } catch (error) { void message.error(error instanceof Error ? error.message : "搜索失败"); } finally { setSearching(false); } };
  const memberActions = (member: TeamMember) => member.role === "owner" || !canManage ? [] : [
    <Select key="role" size="small" value={member.role} options={["admin", "editor", "viewer"].map((role) => ({ value: role, label: roleLabel[role as TeamRole] }))} onChange={(role) => updateMember.mutate({ spaceId, memberId: member.id, role }, { onError: (e) => void message.error(e.message) })} />,
    ...(space.data.role === "owner" ? [<Popconfirm key="transfer" title={`将所有权转让给 ${member.name}？`} onConfirm={() => transfer.mutate({ spaceId, memberId: member.id })}><Button type="text" icon={<SwapOutlined />} aria-label="转让所有权" /></Popconfirm>] : []),
    <Popconfirm key="remove" title={`移除成员 ${member.name}？`} onConfirm={() => removeMember.mutate({ spaceId, memberId: member.id })}><Button danger type="text" icon={<DeleteOutlined />} aria-label="移除成员" /></Popconfirm>,
  ];
  const items = [
    { key: "assets", label: "图片资产", children: pictures.isLoading ? <Skeleton active /> : pictures.data?.length ? <div className="personal-grid">{pictures.data.map((picture, index) => <PictureTile key={picture.id} picture={picture} priority={index === 0} showStatus variant="workspace" action={canEdit ? <Button type="link" icon={<EditOutlined />} href={`/editor/${picture.id}`}>编辑</Button> : undefined} />)}</div> : <Empty description="团队空间还没有图片" /> },
    { key: "members", label: `成员 ${space.data.memberCount}`, children: <div className="team-members"><div className="team-section-actions">{canManage ? <Button type="primary" icon={<UserAddOutlined />} onClick={() => setInviteOpen(true)}>邀请成员</Button> : null}</div><List loading={members.isLoading} dataSource={members.data?.items ?? []} renderItem={(member) => <List.Item actions={memberActions(member)}><List.Item.Meta avatar={<Avatar src={member.avatarUrl}>{member.name.slice(0, 1)}</Avatar>} title={<Space><span>{member.name}</span><Tag>{roleLabel[member.role]}</Tag></Space>} description={member.accountMasked} /></List.Item>} /></div> },
    { key: "settings", label: "空间设置", children: <div className="team-settings"><h2>永久删除团队空间</h2><p>空间、图片、草稿和历史版本将不可恢复。MinIO 资产由后台可靠清理。</p>{space.data.role === "owner" ? <><Input value={deleteName} onChange={(event) => setDeleteName(event.target.value)} placeholder={`输入“${space.data.name}”确认`} /><Button danger icon={<DeleteOutlined />} disabled={deleteName !== space.data.name} loading={deletion.isPending} onClick={() => deletion.mutate({ spaceId, name: deleteName }, { onSuccess: () => { void message.success("团队空间已永久删除"); router.push("/spaces/team"); }, onError: (e) => void message.error(e.message) })}>永久删除</Button></> : <Tag>仅所有者可操作</Tag>}</div> },
  ];
  return <main className="content-shell team-shell"><section className="page-heading"><div><p className="page-kicker">TEAM SPACE</p><h1>{space.data.name}</h1><p><TeamOutlined /> {space.data.memberCount} 位成员 · {space.data.totalCount} 张图片</p></div>{canEdit ? <Button type="primary" size="large" icon={<UploadOutlined />} href={`/upload?spaceId=${spaceId}`}>上传图片</Button> : null}</section><Tabs items={items} />
    <Modal title="邀请成员" open={inviteOpen} onCancel={() => setInviteOpen(false)} footer={null} width={620}><Space.Compact block><Input value={query} onChange={(event) => setQuery(event.target.value)} onPressEnter={() => void search()} placeholder="输入完整账号或昵称" /><Button icon={<SearchOutlined />} loading={searching} onClick={() => void search()}>搜索</Button></Space.Compact><Select className="invite-role-select" value={inviteRole} onChange={setInviteRole} options={[{ value: "viewer", label: "浏览者" }, { value: "editor", label: "编辑者" }]} /><List dataSource={results} locale={{ emptyText: "搜索用户后显示结果" }} renderItem={(user) => <List.Item actions={[<Button key="invite" type="primary" size="small" disabled={user.relationship !== "none"} loading={invite.isPending && invite.variables?.inviteeId === user.id} onClick={() => invite.mutate({ spaceId, inviteeId: user.id, role: inviteRole }, { onSuccess: () => void message.success("邀请已发送"), onError: (e) => void message.error(e.message) })}>{user.relationship === "member" ? "已加入" : user.relationship === "pending" ? "已邀请" : "邀请"}</Button>]}><List.Item.Meta avatar={<Avatar src={user.avatarUrl}>{user.name.slice(0, 1)}</Avatar>} title={user.name} description={user.accountMasked} /></List.Item>} /></Modal>
  </main>;
}
