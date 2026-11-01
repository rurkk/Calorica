"""Validate or export the hand-edited draw.io sources without regenerating them."""
import argparse
import base64
from pathlib import Path
import shutil
import subprocess
from urllib.parse import unquote
import xml.etree.ElementTree as ET
import zlib

HERE = Path(__file__).resolve().parent
NAMES = ("context", "containers", "server-components", "data-model",
         "data-model-diary", "android", "deployment")


def graph(path):
    root = ET.parse(path).getroot()
    if root.tag == "mxGraphModel":
        return root
    model = root.find(".//mxGraphModel")
    if model is not None:
        return model
    page = root.find("diagram")
    return ET.fromstring(unquote(zlib.decompress(base64.b64decode(page.text), -15).decode()))


def validate(path):
    cells = graph(path).find("root").findall("mxCell")
    ids = [cell.get("id") for cell in cells]
    if len(ids) != len(set(ids)):
        raise ValueError(f"Duplicate cell identifiers: {path}")
    for cell in cells:
        for attribute in ("parent", "source", "target"):
            value = cell.get(attribute)
            if value is not None and value not in ids:
                raise ValueError(f"Missing {attribute} {value}: {path}")
        if cell.get("edge") == "1":
            if cell.find("mxGeometry") is None:
                raise ValueError(f"Missing edge geometry: {path}")
            if not cell.get("source") or not cell.get("target"):
                raise ValueError(f"Detached edge: {path}, {cell.get('id')}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", action="store_true", help="Export PNGs with embedded XML")
    parser.add_argument("--drawio", default=shutil.which("drawio") or "/Applications/draw.io.app/Contents/MacOS/draw.io")
    args = parser.parse_args()
    for name in NAMES:
        source = HERE / "diagrams" / f"{name}.drawio"
        validate(source)
        if args.export:
            subprocess.run([args.drawio, "--disable-gpu", "--no-sandbox", "-x", "-f", "png",
                            "-e", "-b", "10", "-s", "2", "--theme", "light", "-o",
                            str(HERE / "assets" / f"{name}.drawio.png"), str(source)], check=True)
    print(f"Validated {len(NAMES)} draw.io sources; native files were not modified")


if __name__ == "__main__":
    main()
