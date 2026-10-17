import React, { createElement, useMemo } from "react";
import { View } from "react-native";

import type { SkImage } from "../skia/types";

import type { SkiaImageViewProps } from "./types";

// One encode per image, however many views show it: the browser decodes the
// URL once for every <img> showing it. Dropped with the image.
const encodedUrls = new WeakMap<SkImage, string>();

const resolveEncodedUrl = (image: SkImage) => {
  let url = encodedUrls.get(image);
  if (url === undefined) {
    url = `data:image/png;base64,${image.encodeToBase64()}`;
    encodedUrls.set(image, url);
  }
  return url;
};

const IMAGE_STYLE = { width: "100%", height: "100%", display: "block" };

/**
 * A view showing an image, with no canvas of its own: on the web, an <img> of
 * the image, encoded once however many views show it. The image fills the
 * view's bounds.
 */
export const SkiaImageView = ({ image, ...viewProps }: SkiaImageViewProps) => {
  const url = useMemo(
    () => (image === null ? undefined : resolveEncodedUrl(image)),
    [image]
  );
  return (
    <View {...viewProps}>
      {url === undefined
        ? null
        : createElement("img", { src: url, alt: "", style: IMAGE_STYLE })}
    </View>
  );
};
