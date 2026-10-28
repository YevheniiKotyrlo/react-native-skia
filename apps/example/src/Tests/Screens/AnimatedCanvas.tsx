import React from "react";
import { Canvas, Fill, Rect } from "react-native-skia";
import {
  useDerivedValue,
  useFrameCallback,
  useSharedValue,
} from "react-native-reanimated";

// The time a cyan square takes to cross the canvas: not a whole number of
// seconds, so two snapshots taken whole seconds apart still find it moved.
const PASS_MS = 1370;

// A canvas whose content changes on every UI frame: a cyan square crossing a
// red canvas.
export const AnimatedCanvas = () => {
  const time = useSharedValue(0);
  useFrameCallback((frame) => {
    time.value = frame.timeSinceFirstFrame;
  });
  const x = useDerivedValue(() => ((time.value % PASS_MS) / PASS_MS) * 600);
  return (
    <Canvas style={{ flex: 1 }}>
      <Fill color="red" />
      <Rect x={x} y={0} width={120} height={120} color="cyan" />
    </Canvas>
  );
};
