import { EyeOutlined, HeartOutlined } from "@ant-design/icons";
import Link from "next/link";
import type { PrototypePicture } from "@/features/prototype";
import { PictureImage } from "@/features/prototype/ui/picture-image";
import { PublishStatusTag } from "@/features/prototype/ui/publish-status-tag";

interface PictureTileProps {
  action?: React.ReactNode;
  picture: PrototypePicture;
  priority?: boolean;
  showStatus?: boolean;
}

export function PictureTile({ action, picture, priority, showStatus = false }: PictureTileProps) {
  return (
    <article className="picture-tile">
      <Link href={`/pictures/${picture.id}`} className="picture-tile-link">
        <div
          className="picture-tile-media"
          style={{ aspectRatio: `${picture.width} / ${picture.height}` }}
        >
          <PictureImage alt={picture.title} priority={priority} src={picture.imageUrl} />
        </div>
        <div className="picture-tile-body">
          <div className="picture-title-line">
            <h2>{picture.title}</h2>
            {showStatus ? <PublishStatusTag status={picture.publishStatus} /> : null}
          </div>
          <div className="picture-meta-line">
            <span>{picture.authorName}</span>
            {picture.publishStatus === "approved" ? (
              <span className="picture-signals">
                <span>
                  <EyeOutlined /> {picture.views}
                </span>
                <span>
                  <HeartOutlined /> {picture.likes}
                </span>
              </span>
            ) : (
              <span>{new Date(picture.createdAt).toLocaleDateString("zh-CN")}</span>
            )}
          </div>
        </div>
      </Link>
      {action ? <div className="picture-tile-action">{action}</div> : null}
    </article>
  );
}
