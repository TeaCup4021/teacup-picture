"use client";

import Image from "next/image";
import { useState } from "react";
import { useSessionContext, withSessionContext } from "@/api/session-context";

interface PictureImageProps {
  alt: string;
  className?: string;
  priority?: boolean;
  src: string;
  /** Optional visual fallback for existing gallery cards. Empty string disables fallback. */
  fallbackSrc?: string;
  onError?: () => void;
}

export function PictureImage({ alt, className, fallbackSrc = "/mock-images/gallery-08.jpg", onError, priority, src }: PictureImageProps) {
  const [failedSource, setFailedSource] = useState<string | null>(null);
  const sessionContext = useSessionContext();
  const resolvedSource =
    failedSource === src && fallbackSrc ? fallbackSrc : withSessionContext(src, sessionContext);

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
      onError={() => {
        onError?.();
        if (fallbackSrc && sessionContext) setFailedSource(src);
      }}
    />
  );
}
