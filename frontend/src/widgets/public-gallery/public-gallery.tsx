"use client";

import { Alert, Empty, Skeleton } from "antd";
import { usePublicPictures } from "@/features/prototype";
import { PictureTile } from "@/features/prototype/ui/picture-tile";

export function PublicGallery() {
  const pictures = usePublicPictures();

  return (
    <main className="content-shell">
      <section className="page-heading" aria-labelledby="gallery-title">
        <div>
          <p className="page-kicker">CURATED FOR YOU</p>
          <h1 id="gallery-title">公开图库</h1>
          <p className="gallery-lead">发现值得收藏的视觉灵感</p>
          <p>探索经过审核的最新作品</p>
        </div>
        <span className="result-count">{pictures.data?.length ?? 0} 张公开图片</span>
      </section>
      {pictures.isError ? <Alert type="error" showIcon title="公开图库加载失败" /> : null}
      {pictures.isLoading ? (
        <div className="gallery-grid" aria-label="正在加载公开图库">
          {Array.from({ length: 8 }).map((_, index) => (
            <div className="gallery-skeleton" key={index}>
              <Skeleton.Image active />
            </div>
          ))}
        </div>
      ) : pictures.data?.length ? (
        <div className="gallery-grid">
          {pictures.data?.map((picture, index) => (
            <PictureTile key={picture.id} picture={picture} priority={index === 0} />
          ))}
        </div>
      ) : (
        <div className="gallery-empty">
          <Empty description="暂无公开图片" />
        </div>
      )}
    </main>
  );
}
