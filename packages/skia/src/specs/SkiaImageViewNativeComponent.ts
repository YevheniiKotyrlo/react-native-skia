import { codegenNativeComponent, type ViewProps } from "react-native";
import type {
  Double,
  WithDefault,
} from "react-native/Libraries/Types/CodegenTypes";

// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore - pointerEvents needs to be redeclared for codegen to generate native bindings
export interface NativeProps extends ViewProps {
  // The id the view shows its image by (SkiaViewApi.presentedImageId); 0
  // shows nothing.
  presentedImageId?: Double;
  pointerEvents?: WithDefault<
    "auto" | "none" | "box-none" | "box-only",
    "auto"
  >;
}

// eslint-disable-next-line import/no-default-export
export default codegenNativeComponent<NativeProps>("SkiaImageView");
