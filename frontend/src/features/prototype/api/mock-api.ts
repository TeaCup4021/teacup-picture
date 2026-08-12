import type {
  LoginInput,
  PrototypeDatabase,
  PrototypePicture,
  PrototypeUser,
  UploadPictureInput,
} from "@/features/prototype/model/types";

const DATABASE_KEY = "teacup-picture.prototype-db.v1";
const REQUEST_DELAY = 120;

const users: PrototypeUser[] = [
  {
    id: "10001",
    account: "muyi",
    displayName: "木一",
    role: "user",
    avatarText: "木",
  },
  {
    id: "90001",
    account: "admin",
    displayName: "审核管理员",
    role: "admin",
    avatarText: "审",
  },
];

const pictures: PrototypePicture[] = [
  {
    id: "20001",
    title: "峡湾之上",
    description: "登上岩壁后看见的峡湾全景，水面与云层留下了清晰的蓝色层次。",
    imageUrl: "/mock-images/gallery-01.jpg",
    width: 1200,
    height: 900,
    authorId: "11001",
    authorName: "北屿",
    spaceId: "31001",
    category: "风景",
    tags: ["旅行", "峡湾"],
    createdAt: "2026-08-09T08:30:00Z",
    views: 1284,
    likes: 236,
    publishStatus: "approved",
  },
  {
    id: "20002",
    title: "雨后露营",
    description: "森林里的短暂停留，裹好毯子等雨停。",
    imageUrl: "/mock-images/gallery-02.jpg",
    width: 900,
    height: 1200,
    authorId: "11002",
    authorName: "柚子",
    spaceId: "31002",
    category: "生活",
    tags: ["宠物", "露营"],
    createdAt: "2026-08-08T11:20:00Z",
    views: 3421,
    likes: 684,
    publishStatus: "approved",
  },
  {
    id: "20003",
    title: "瀑布与虹",
    description: "逆光下的水雾形成一道完整的彩虹。",
    imageUrl: "/mock-images/gallery-03.jpg",
    width: 1200,
    height: 800,
    authorId: "11003",
    authorName: "林深",
    spaceId: "31003",
    category: "风景",
    tags: ["瀑布", "冰岛"],
    createdAt: "2026-08-07T03:45:00Z",
    views: 2190,
    likes: 419,
    publishStatus: "approved",
  },
  {
    id: "20004",
    title: "林间城堡",
    description: "从山路转角远望城堡与周围的森林。",
    imageUrl: "/mock-images/gallery-04.jpg",
    width: 900,
    height: 1200,
    authorId: "11004",
    authorName: "南风",
    spaceId: "31004",
    category: "建筑",
    tags: ["城堡", "欧洲"],
    createdAt: "2026-08-06T06:10:00Z",
    views: 986,
    likes: 155,
    publishStatus: "approved",
  },
  {
    id: "20005",
    title: "城市日落",
    description: "高处俯瞰城市入夜前的最后一束光。",
    imageUrl: "/mock-images/gallery-05.jpg",
    width: 1200,
    height: 900,
    authorId: "11005",
    authorName: "十七楼",
    spaceId: "31005",
    category: "城市",
    tags: ["天际线", "日落"],
    createdAt: "2026-08-05T10:50:00Z",
    views: 1795,
    likes: 328,
    publishStatus: "approved",
  },
  {
    id: "20006",
    title: "夏日莓果",
    description: "清晨市场里刚刚摆上货架的新鲜草莓。",
    imageUrl: "/mock-images/gallery-06.jpg",
    width: 1200,
    height: 800,
    authorId: "11006",
    authorName: "青禾",
    spaceId: "31006",
    category: "静物",
    tags: ["食物", "红色"],
    createdAt: "2026-08-04T00:25:00Z",
    views: 1120,
    likes: 207,
    publishStatus: "approved",
  },
  {
    id: "21001",
    title: "牧场清晨",
    description: "个人空间中的一张清晨练习，尚未提交公开。",
    imageUrl: "/mock-images/gallery-07.jpg",
    width: 900,
    height: 1200,
    authorId: "10001",
    authorName: "木一",
    spaceId: "30001",
    category: "风景",
    tags: ["清晨", "田野"],
    createdAt: "2026-08-10T01:15:00Z",
    views: 0,
    likes: 0,
    publishStatus: "not_requested",
  },
  {
    id: "21002",
    title: "安静的桌面",
    description: "黑白静物练习，等待管理员审核。",
    imageUrl: "/mock-images/gallery-08.jpg",
    width: 1200,
    height: 900,
    authorId: "10001",
    authorName: "木一",
    spaceId: "30001",
    category: "静物",
    tags: ["黑白", "桌面"],
    createdAt: "2026-08-10T07:40:00Z",
    views: 0,
    likes: 0,
    publishStatus: "pending",
  },
];

let memoryDatabase: PrototypeDatabase | undefined;

function createSeedDatabase(): PrototypeDatabase {
  return structuredClone({ users, pictures, sessionUserId: null });
}

function readDatabase(): PrototypeDatabase {
  if (memoryDatabase) return memoryDatabase;

  if (typeof window !== "undefined") {
    const stored = window.localStorage.getItem(DATABASE_KEY);
    if (stored) {
      try {
        memoryDatabase = JSON.parse(stored) as PrototypeDatabase;
        return memoryDatabase;
      } catch {
        window.localStorage.removeItem(DATABASE_KEY);
      }
    }
  }

  memoryDatabase = createSeedDatabase();
  return memoryDatabase;
}

function writeDatabase(database: PrototypeDatabase): void {
  memoryDatabase = database;
  if (typeof window !== "undefined") {
    try {
      window.localStorage.setItem(DATABASE_KEY, JSON.stringify(database));
    } catch {
      // Large local preview images may exceed localStorage; memory state remains usable.
    }
  }
}

async function delay(): Promise<void> {
  await new Promise((resolve) => window.setTimeout(resolve, REQUEST_DELAY));
}

function requireSession(database: PrototypeDatabase): PrototypeUser {
  const user = database.users.find((candidate) => candidate.id === database.sessionUserId);
  if (!user) throw new Error("请先登录");
  return user;
}

export const prototypeApi = {
  async login(input: LoginInput): Promise<PrototypeUser> {
    await delay();
    const validPassword = input.account === "admin" ? "admin123" : "demo123";
    const user = readDatabase().users.find((candidate) => candidate.account === input.account);
    if (!user || input.password !== validPassword) throw new Error("账号或密码错误");

    const database = readDatabase();
    writeDatabase({ ...database, sessionUserId: user.id });
    return user;
  },

  async logout(): Promise<void> {
    await delay();
    const database = readDatabase();
    writeDatabase({ ...database, sessionUserId: null });
  },

  async getSession(): Promise<PrototypeUser | null> {
    await delay();
    const database = readDatabase();
    return database.users.find((user) => user.id === database.sessionUserId) ?? null;
  },

  async getPublicPictures(): Promise<PrototypePicture[]> {
    await delay();
    return readDatabase()
      .pictures.filter((picture) => picture.publishStatus === "approved")
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt));
  },

  async getPicture(pictureId: string): Promise<PrototypePicture | null> {
    await delay();
    return readDatabase().pictures.find((picture) => picture.id === pictureId) ?? null;
  },

  async getPersonalPictures(): Promise<PrototypePicture[]> {
    await delay();
    const database = readDatabase();
    const user = requireSession(database);
    return database.pictures
      .filter((picture) => picture.authorId === user.id)
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt));
  },

  async uploadPicture(input: UploadPictureInput): Promise<PrototypePicture> {
    await delay();
    const database = readDatabase();
    const user = requireSession(database);
    const picture: PrototypePicture = {
      ...input,
      id: `3${Date.now()}`,
      authorId: user.id,
      authorName: user.displayName,
      spaceId: "30001",
      createdAt: new Date().toISOString(),
      views: 0,
      likes: 0,
      publishStatus: "not_requested",
    };
    writeDatabase({ ...database, pictures: [picture, ...database.pictures] });
    return picture;
  },

  async submitReview(pictureId: string): Promise<PrototypePicture> {
    await delay();
    const database = readDatabase();
    const user = requireSession(database);
    const picture = database.pictures.find((candidate) => candidate.id === pictureId);
    if (!picture || picture.authorId !== user.id) throw new Error("图片不存在或无权操作");
    if (picture.publishStatus !== "not_requested" && picture.publishStatus !== "rejected") {
      throw new Error("当前状态不能提交审核");
    }
    picture.publishStatus = "pending";
    picture.reviewNote = undefined;
    writeDatabase({ ...database });
    return picture;
  },

  async getPendingReviews(): Promise<PrototypePicture[]> {
    await delay();
    const database = readDatabase();
    const user = requireSession(database);
    if (user.role !== "admin") throw new Error("没有管理员权限");
    return database.pictures
      .filter((picture) => picture.publishStatus === "pending")
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt));
  },

  async decideReview(input: {
    pictureId: string;
    decision: "approve" | "reject";
    note?: string;
  }): Promise<PrototypePicture> {
    await delay();
    const database = readDatabase();
    const user = requireSession(database);
    if (user.role !== "admin") throw new Error("没有管理员权限");
    const picture = database.pictures.find((candidate) => candidate.id === input.pictureId);
    if (!picture || picture.publishStatus !== "pending") throw new Error("审核申请已发生变化");
    picture.publishStatus = input.decision === "approve" ? "approved" : "rejected";
    picture.reviewNote = input.note;
    writeDatabase({ ...database });
    return picture;
  },

  reset(): void {
    memoryDatabase = createSeedDatabase();
    if (typeof window !== "undefined") window.localStorage.removeItem(DATABASE_KEY);
  },
};
