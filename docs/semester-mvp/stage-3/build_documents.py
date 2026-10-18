"""Extend the retained stage-2 DOCX without changing its requirements or styles.

Use bundled Python with python-docx/pypdf. Render with the packaged render_docx.py,
then --check verifies textual coverage, figure presence and template preservation.
"""
import argparse
import hashlib
import json
import re
import sys
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

from docx import Document
from docx.shared import Mm, Pt
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "scripts"))
from build_stage2 import Builder

REFERENCE = HERE.parent / "stage-2/Calorica-semester-MVP-TZ.docx"
REFERENCE_SHA = "d7ade17b96562c0e3c3f32556346228019f9ccdb70dabaf0a725cbff4fca0ceb"
EDITABLE_PARTS = {"word/document.xml", "word/_rels/document.xml.rels", "[Content_Types].xml", "docProps/core.xml"}
FOUNDATION_OLD = "ТЗ подготовлено для второго этапа учебного проекта: определить объём семестра, функциональные и нефункциональные требования."
FOUNDATION_NEW = "ТЗ объединяет требования второго этапа и архитектуру третьего этапа учебного проекта: технологии, компоненты, модель данных и взаимодействие системы."


def source_zip():
    data = REFERENCE.read_bytes()
    if hashlib.sha256(data).hexdigest() != REFERENCE_SHA:
        raise ValueError("Retained stage-2 reference changed; inspect and update template contract first")
    return ZipFile(BytesIO(data))


def build(output, assets):
    original = source_zip()
    b = Builder()
    b.doc = Document(REFERENCE)
    doc = b.doc
    cover = doc.paragraphs[4]
    cover.runs[0].text = "Семестровый MVP\nТретий этап учебного проекта\nВерсия 1.2 · 18 октября 2026 года"
    for paragraph in doc.paragraphs:
        if paragraph.text == FOUNDATION_OLD:
            paragraph.runs[0].text = FOUNDATION_NEW
            for run in paragraph.runs[1:]:
                run.text = ""
    doc.core_properties.subject = "Третий этап Архитектура семестрового MVP"
    doc.core_properties.keywords = "Calorica, техническое задание, этап 3, архитектура"
    architecture = (HERE / "architecture.md").read_text()
    parts = re.split(r"^## (.+)$", architecture, flags=re.M)
    for heading, content in zip(parts[1::2], parts[2::2]):
        p = doc.add_heading(heading, level=1)
        p.paragraph_format.page_break_before = True
        for piece in re.split(r"(!\[[^\]]*\]\([^)]+\))", content):
            match = re.fullmatch(r"!\[([^\]]*)\]\(([^)]+)\)", piece.strip())
            if match:
                png = assets / Path(match[2]).name
                p = doc.add_paragraph()
                p.paragraph_format.keep_with_next = True
                inline = p.add_run().add_picture(str(png), width=Mm(165))
                inline._inline.docPr.set("descr", match[1])
                caption = doc.add_paragraph(match[1])
                caption.paragraph_format.space_after = Pt(9)
                caption.runs[0].font.size = Pt(11)
            elif piece.strip():
                b.markdown(piece, HERE / "architecture.md", base=2)
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = BytesIO()
    doc.save(temp)
    edited = ZipFile(BytesIO(temp.getvalue()))
    # Keep all unrelated OOXML parts byte-for-byte, including customXml and footer PAGE.
    with ZipFile(output, "w", ZIP_DEFLATED) as final:
        for name in edited.namelist():
            data = original.read(name) if name in original.namelist() and name not in EDITABLE_PARTS else edited.read(name)
            final.writestr(name, data)
    print(output)


def check(output):
    original = source_zip()
    final = ZipFile(output)
    errors = []
    for part in original.namelist():
        if part not in final.namelist():
            errors.append("Missing original part: " + part)
        elif part not in EDITABLE_PARTS and original.read(part) != final.read(part):
            errors.append("Changed preserve-only part: " + part)
    reference_doc = Document(REFERENCE)
    doc = Document(output)
    text = "\n".join(p.text for p in doc.paragraphs)
    text += "\n" + "\n".join(c.text for t in doc.tables for r in t.rows for c in r.cells)
    pdf = PdfReader(output.with_suffix(".pdf"))
    pdf_text = "\n".join(p.extract_text() for p in pdf.pages)
    norm = lambda s: re.sub(r"\s+", "", s.replace("\u00ad", "").replace("**", "").replace("`", ""))
    for paragraph in reference_doc.paragraphs[8:]:
        expected = FOUNDATION_NEW if paragraph.text == FOUNDATION_OLD else paragraph.text
        if norm(expected) not in norm(text):
            errors.append("Lost stage-2 text: " + paragraph.text[:60])
    for table in reference_doc.tables:
        for row in table.rows:
            for cell in row.cells:
                if norm(cell.text) not in norm(text): errors.append("Lost stage-2 table cell: " + cell.text)
    ids = {f"SM-{i:02}" for i in range(1,10)} | {f"SB-{i:02}" for i in range(1,7)} | {f"SN-{i:02}" for i in range(1,9)}
    md = (HERE / "technical-specification.md").read_text()
    base_md = (HERE.parent / "stage-2/technical-specification.md").read_text()
    base_md = base_md.replace("ТЗ подготовлено для второго этапа учебного проекта: определить объём семестра,\nфункциональные и нефункциональные требования.", "ТЗ объединяет требования второго этапа и архитектуру третьего этапа учебного\nпроекта: технологии, компоненты, модель данных и взаимодействие системы.")
    arch_md = (HERE / "architecture.md").read_text().split("\n", 1)[1]
    if norm(md) != norm(base_md + arch_md): errors.append("MD specification differs from stage-2 requirements plus architecture")
    for label, value in (("MD", md),("DOCX",text),("PDF",pdf_text)):
        if not ids.issubset(set(re.findall(r"(?:SM|SB|SN)-\d{2}",value))): errors.append("Missing requirements: "+label)
    for paragraph in doc.paragraphs:
        value = paragraph.text.strip()
        if value and norm(value) not in norm(pdf_text):
            # Native bullets are represented by a separate glyph during extraction.
            if norm(value.lstrip("• ")) not in norm(pdf_text): errors.append("PDF lost paragraph: " + value[:70])
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                if norm(cell.text) not in norm(pdf_text): errors.append("PDF lost table cell: " + cell.text[:70])
    if len(doc.inline_shapes) != 4: errors.append("Expected four architecture figures")
    if doc.element.xpath(".//w:ins | .//w:del | .//w:pBdr"): errors.append("Unexpected revisions or paragraph borders")
    if len(doc.sections) != 1: errors.append("Changed section count")
    print(json.dumps({"pages":len(pdf.pages),"figures":len(doc.inline_shapes),"requirements":len(ids),"errors":errors},ensure_ascii=False,indent=2))
    if errors: raise SystemExit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--out",type=Path,default=HERE / "Calorica-stage-3-technical-specification.docx")
    parser.add_argument("--assets",type=Path,default=HERE / "assets")
    parser.add_argument("--check",action="store_true")
    args = parser.parse_args()
    check(args.out) if args.check else build(args.out,args.assets)
