"""Ребрендинг строк upstream: baresip / baresip+ -> HHPhone в пользовательском тексте.

Читает app/src/main/res/values{,-ru}/strings.xml, берёт строки со словом baresip,
заменяет его вне URL и пишет переопределения в app/src/hh/res/values{,-ru}/brand_strings.xml.
Запускать после каждого слияния с upstream. Ключи из SKIP задаются руками в hh_strings.xml.

usage: python scripts/brand-strings.py [--selftest]
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKIP = {"app_name", "app_name_plus", "about_title", "about_title_plus", "about_text", "about_text_plus"}
STRING = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.S)
URL = re.compile(r'(href="[^"]*"|https?://\S+)')
WORD = re.compile(r'(?<![\w/.\-])baresip\+?(?![\w/\-]|\.\w)', re.I)


def rebrand(text):
    parts = URL.split(text)
    return "".join(p if URL.fullmatch(p) else WORD.sub("HHPhone", p) for p in parts)


def overrides(xml):
    out = []
    for name, attrs, body in STRING.findall(xml):
        if name in SKIP or 'translatable="false"' in attrs:
            continue
        new = rebrand(body)
        if new != body:
            out.append(f'    <string name="{name}"{attrs}>{new}</string>')
    return out


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    for qual in ("values", "values-ru"):
        src = ROOT / "app/src/main/res" / qual / "strings.xml"
        lines = overrides(src.read_text(encoding="utf-8"))
        dst = ROOT / "app/src/hh/res" / qual / "brand_strings.xml"
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text('<?xml version="1.0" encoding="utf-8"?>\n'
                       "<!-- Сгенерировано scripts/brand-strings.py, руками не править -->\n"
                       "<resources>\n" + "\n".join(lines) + "\n</resources>\n", encoding="utf-8", newline="\n")
        print(f"{dst.relative_to(ROOT)}: {len(lines)} строк")


def selftest():
    assert rebrand("If checked, baresip starts") == "If checked, HHPhone starts"
    assert rebrand("baresip+\\'s Settings") == "HHPhone\\'s Settings"
    assert rebrand('<a href="https://github.com/baresip/baresip">x</a>') == '<a href="https://github.com/baresip/baresip">x</a>'
    assert rebrand("see https://github.com/juha-h/baresip-studio now") == "see https://github.com/juha-h/baresip-studio now"
    assert rebrand("com.tutpro.baresip.plus") == "com.tutpro.baresip.plus"
    assert rebrand("Baresip needs access") == "HHPhone needs access"
    assert rebrand("Then restart baresip. Done") == "Then restart HHPhone. Done"
    assert rebrand("uses baresip.") == "uses HHPhone."
    assert rebrand("Если отмечено, baresip запускается") == "Если отмечено, HHPhone запускается"
    xml = ('<string name="a">baresip runs</string><string name="app_name" translatable="false">baresip</string>'
           '<string name="b" translatable="false">baresip x</string><string name="c">no brand</string>')
    assert overrides(xml) == ['    <string name="a">HHPhone runs</string>']
    print("selftest OK")


if __name__ == "__main__":
    selftest() if "--selftest" in sys.argv else main()
