"use client";

import { Alert, Skeleton } from "antd";
import { usePublicPictures } from "@/features/prototype";
import { PictureTile } from "@/features/prototype/ui/picture-tile";

export function PublicGallery() {
  const pictures = usePublicPictures();

  return (
    <main className="content-shell">
      <section className="page-heading" aria-labelledby="gallery-title">
        <div>
          <p className="page-kicker">DISCOVER</p>
          <h1 id="gallery-title">公开图库</h1>
          <p>发现经过审核的最新作品</p>
        </div>
        <span className="result-count">{pictures.data?.length ?? 0} 张公开图片</span>
      </section>
      {pictures.isError ? <Alert type="error" showIcon title="公开图库加载失败" /> : null}
      {pictures.isLoading ? (
        <div className="gallery-grid" aria-label="正在加载公开图库">
          {Array.from({ length: 8 }).map((_, index) => (
            <Skeleton.Image active className="gallery-skeleton" key={index} />
          ))}
        </div>
      ) : (
        <div className="gallery-grid">
          {pictures.data?.map((picture, index) => (
            <PictureTile key={picture.id} picture={picture} priority={index === 0} />
          ))}
        </div>
      )}
    </main>
  );
}
