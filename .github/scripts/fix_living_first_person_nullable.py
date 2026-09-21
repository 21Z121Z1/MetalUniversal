from pathlib import Path

path = Path("src/main/java/com/metallum/client/metal/render/MetalSyntheticExactMotion.java")
text = path.read_text()
old = "public static @Nullable MetalEntityMotionCapture.Sample beginFirstPerson(final InteractionHand hand)"
new = "public static MetalEntityMotionCapture.@Nullable Sample beginFirstPerson(final InteractionHand hand)"
count = text.count(old)
if count != 1:
    raise SystemExit(f"expected exactly one qualified @Nullable anchor, found {count}")
path.write_text(text.replace(old, new, 1))
