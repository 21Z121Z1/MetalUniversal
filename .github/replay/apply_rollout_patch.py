import pathlib
import sys

ROOT = pathlib.Path.cwd()
PREFIX = "/metaluniversal-heap-alias-recipe-canonical/"


def relpath(raw: str) -> pathlib.Path:
    if PREFIX in raw:
        raw = raw.split(PREFIX, 1)[1]
    path = pathlib.Path(raw)
    if path.is_absolute():
        raise RuntimeError(f"unrecognized absolute patch path: {raw}")
    return ROOT / path


def apply_patch(text: str) -> None:
    lines = text.splitlines()
    if not lines or lines[0] != "*** Begin Patch" or lines[-1] != "*** End Patch":
        raise RuntimeError("invalid patch envelope")
    i = 1
    while i < len(lines) - 1:
        line = lines[i]
        if line.startswith("*** Add File: "):
            path = relpath(line[len("*** Add File: "):])
            i += 1
            body = []
            while i < len(lines) - 1 and not lines[i].startswith("*** "):
                item = lines[i]
                if not item.startswith("+"):
                    raise RuntimeError(f"bad add line for {path}: {item!r}")
                body.append(item[1:])
                i += 1
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.exists():
                raise RuntimeError(f"add target already exists: {path}")
            path.write_text("\n".join(body) + "\n")
            continue
        if line.startswith("*** Delete File: "):
            path = relpath(line[len("*** Delete File: "):])
            i += 1
            if not path.exists():
                raise RuntimeError(f"delete target missing: {path}")
            path.unlink()
            continue
        if line.startswith("*** Update File: "):
            path = relpath(line[len("*** Update File: "):])
            i += 1
            if not path.exists():
                raise RuntimeError(f"update target missing: {path}")
            data = path.read_text()
            while i < len(lines) - 1 and not lines[i].startswith("*** "):
                if lines[i] != "@@":
                    raise RuntimeError(f"expected @@ for {path}, got {lines[i]!r}")
                i += 1
                hunk = []
                while i < len(lines) - 1 and lines[i] != "@@" and not lines[i].startswith("*** "):
                    hunk.append(lines[i])
                    i += 1
                old, new = [], []
                for item in hunk:
                    if item.startswith("-"):
                        old.append(item[1:])
                    elif item.startswith("+"):
                        new.append(item[1:])
                    elif item.startswith(" "):
                        old.append(item[1:])
                        new.append(item[1:])
                    else:
                        raise RuntimeError(f"bad hunk line for {path}: {item!r}")
                old_text = "\n".join(old)
                new_text = "\n".join(new)
                needle = old_text + "\n"
                replacement = new_text + ("\n" if new else "")
                if needle in data:
                    data = data.replace(needle, replacement, 1)
                elif old_text in data:
                    data = data.replace(old_text, new_text, 1)
                else:
                    raise RuntimeError(f"hunk not found for {path}: {old_text[:160]!r}")
            path.write_text(data)
            continue
        raise RuntimeError(f"unknown patch command: {line}")


for patch_path in sys.argv[1:]:
    print(f"applying {patch_path}")
    apply_patch(pathlib.Path(patch_path).read_text())
