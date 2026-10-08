"""Переводимые строки из values/, которых нет в ru или hy (main + hh вместе).

usage: python scripts/missing-strings.py   -> код 1 и список, если есть пропуски
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
STRING = re.compile(r'<string name="([^"]+)"([^>]*)>', re.S)


def keys(qual, translatable_only=False):
    found = set()
    for base in ("app/src/main/res", "app/src/hh/res"):
        d = ROOT / base / qual
        for f in d.glob("*.xml") if d.exists() else []:
            for name, attrs in STRING.findall(f.read_text(encoding="utf-8")):
                if not (translatable_only and 'translatable="false"' in attrs):
                    found.add(name)
    return found


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    base = keys("values", translatable_only=True)
    bad = 0
    for lang in ("ru", "hy"):
        missing = sorted(base - keys(f"values-{lang}"))
        bad += len(missing)
        print(f"{lang}: {len(missing)} пропущено", *missing, sep="\n  ")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
