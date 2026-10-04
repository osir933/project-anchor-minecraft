"""Prints the shape of JSON data files found in jars: keys, value types and short scalar values, so a data
format can be followed without copying the files. Usage: json_shape.py JAR PATH..."""
import json
import sys
import zipfile


def shape(value, indent, out, depth=0):
    pad = "  " * indent
    if isinstance(value, dict):
        for key, item in value.items():
            if isinstance(item, (dict, list)):
                out.append(f"{pad}{key}: {type(item).__name__}" + (f"[{len(item)}]" if isinstance(item, list) else ""))
                if depth < 7:
                    shape(item, indent + 1, out, depth + 1)
            else:
                out.append(f"{pad}{key}: {scalar(item)}")
    elif isinstance(value, list):
        if value:
            out.append(f"{pad}- first of {len(value)}:")
            if isinstance(value[0], (dict, list)):
                shape(value[0], indent + 1, out, depth + 1)
            else:
                out.append(f"{pad}  {scalar(value[0])}")


def scalar(item):
    text = json.dumps(item)
    return text if len(text) <= 48 else f"{type(item).__name__} ({len(text)} chars)"


def main():
    jar = zipfile.ZipFile(sys.argv[1])
    names = set(jar.namelist())
    for path in sys.argv[2:]:
        if path.endswith("/"):
            found = sorted(n for n in names if n.startswith(path))
            print(f"=== {path}: {len(found)} entries: " + " ".join(n[len(path):] for n in found[:80]))
            continue
        print(f"=== {path}")
        if path not in names:
            print("  (missing)")
            continue
        out = []
        shape(json.loads(jar.read(path)), 1, out)
        print("\n".join(out))


if __name__ == "__main__":
    main()
