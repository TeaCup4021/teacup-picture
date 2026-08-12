"use client";

import Image from "next/image";
import { useState } from "react";

interface PictureImageProps {
  alt: string;
  className?: string;
  priority?: boolean;
  src: string;
}

export function PictureImage({ alt, className, priority, src }: PictureImageProps) {
  const [resolvedSource, setResolvedSource] = useState(src);

  return (
    <Image
      alt={alt}
      className={className}
      fill
      loading={priority ? "eager" : "lazy"}
      sizes="(max-width: 720px) 100vw, (max-width: 1100px) 50vw, 25vw"
      src={resolvedSource}
      unoptimized={
        resolvedSource.startsWith("/mock-images/") ||
        resolvedSource.startsWith("data:") ||
        resolvedSource.startsWith("http")
      }
      onError={() => setResolvedSource("/mock-images/gallery-08.jpg")}
    />
  );
}
