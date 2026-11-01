"""Extend the stage-2 specification with the concise stage-3 architecture.

Use bundled Python with python-docx/pypdf. Render with the packaged render_docx.py,
then --check compares the retained requirements and current architecture in all formats.
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
from docx.enum.section import WD_ORIENT, WD_SECTION_START
from docx.enum.text import WD_ALIGN_PARAGRAPH
from pypdf import PdfReader
from lxml import etree

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "scripts"))
from document_builder import Builder

REFERENCE = HERE.parent / "stage-2/Calorica-semester-MVP-TZ.docx"
REFERENCE_MD = HERE.parent / "stage-2/technical-specification.md"
REFERENCE_SHA = "d7ade17b96562c0e3c3f32556346228019f9ccdb70dabaf0a725cbff4fca0ceb"
REFERENCE_MD_SHA = "05a48619e80402d753d09a9096ec83b000a8a64683f18fd6f241f48889fccdf2"
EDITABLE_PARTS = {"word/document.xml", "word/_rels/document.xml.rels", "[Content_Types].xml", "docProps/core.xml", "word/settings.xml"}
FOUNDATION_OLD = "ТЗ подготовлено для второго этапа учебного проекта: определить объём семестра, функциональные и нефункциональные требования."
FOUNDATION_NEW = "ТЗ объединяет требования второго этапа и архитектуру третьего этапа учебного проекта: технологии, компоненты, модель данных и взаимодействие системы."
COVER = "Семестровый MVP\nТретий этап учебного проекта\nВерсия 1.7 · 23 октября 2026 года"
# These are the only stage-3 changes to retained prose. The user reconfirmed
# omission of database copies on 23 October; all other requirements stay verbatim.
STAGE3_REVISIONS = {
    FOUNDATION_OLD: FOUNDATION_NEW,
    "Копирование БД — ежедневно и перед миграциями; хотя бы одна защищённая копия вне VPS. Проверка восстановления на отдельной БД обязательна. ": "",
    "запуска, миграции, выпуска и восстановления.": "запуска, миграции и выпуска.",
    "Независимый CI/CD, подписанный APK, сервер, миграции и восстановление копии": "Независимый CI/CD, подписанный APK, сервер, миграции и проверка перезапуска",
    "Сетевые ошибки, независимый выпуск, сохранность и восстановление": "Сетевые ошибки, независимый выпуск, сохранность при перезапуске",
    "Все SA-01 — SA-17 обязательны; подробные шаги хранятся в семестровом плане приёмки.": "SA-01 — SA-16 проверяются по семестровому плану приёмки; SA-17 — сохранность записей после перезапуска сервера и БД.",
}
EXPECTED_IDS = ({f"SM-{i:02}" for i in range(1,10)}
                | {f"SB-{i:02}" for i in range(1,7)}
                | {f"SN-{i:02}" for i in range(1,9)})
IMAGE = r"!\[([^\]]*)\]\(([^)]+)\)"


def revision_pattern(old):
    pattern = r"\s+".join(re.escape(word) for word in old.split())
    return pattern + (r"\s+" if old.endswith(" ") else "")


def revised(text):
    """Apply only the explicitly requested stage-3 revisions to a copy."""
    for old, new in STAGE3_REVISIONS.items():
        text = re.sub(revision_pattern(old), lambda _: new, text)
    return text


def revise_paragraph(paragraph):
    old = paragraph.text
    new = revised(old)
    if old == new:
        return
    runs = [run for run in paragraph.runs if run.text]
    # Preserve the bold requirement label and the original body formatting.
    if len(runs) > 1 and new.startswith(runs[0].text):
        runs[1].text = new[len(runs[0].text):]
        for run in runs[2:]:
            run.text = ""
    elif runs:
        runs[0].text = new
        for run in runs[1:]:
            run.text = ""


def sync_markdown():
    source_zip()
    base = REFERENCE_MD.read_text()
    arch = (HERE / "architecture.md").read_text().split("\n", 1)[1]
    (HERE / "technical-specification.md").write_text(revised(base).rstrip() + "\n" + arch)


def source_zip():
    data = REFERENCE.read_bytes()
    if hashlib.sha256(data).hexdigest() != REFERENCE_SHA:
        raise ValueError("Retained stage-2 reference changed; inspect and update template contract first")
    if hashlib.sha256(REFERENCE_MD.read_bytes()).hexdigest() != REFERENCE_MD_SHA:
        raise ValueError("Retained stage-2 Markdown changed; inspect the source contract")
    reference_doc = Document(REFERENCE)
    reference_text = "\n".join(p.text for p in reference_doc.paragraphs)
    reference_text += "\n" + "\n".join(c.text for t in reference_doc.tables for r in t.rows for c in r.cells)
    for old in STAGE3_REVISIONS:
        for label, text in (("DOCX", reference_text), ("Markdown", REFERENCE_MD.read_text())):
            if len(re.findall(revision_pattern(old), text)) != 1:
                raise ValueError(f"Stage-3 revision must match exactly once in {label}: {old}")
    return ZipFile(BytesIO(data))


def build(output, assets):
    original = source_zip()
    b = Builder()
    b.doc = Document(REFERENCE)
    doc = b.doc
    cover = doc.paragraphs[4]
    cover.runs[0].text = COVER
    for run in cover.runs[1:]:
        run.text = ""
    for paragraph in doc.paragraphs:
        revise_paragraph(paragraph)
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                for paragraph in cell.paragraphs:
                    revise_paragraph(paragraph)
    doc.core_properties.subject = "Третий этап Архитектура семестрового MVP"
    doc.core_properties.keywords = "Calorica, техническое задание, этап 3, архитектура"
    architecture = (HERE / "architecture.md").read_text()
    parts = re.split(r"^## (.+)$", architecture, flags=re.M)
    portrait_width, portrait_height = doc.sections[0].page_width, doc.sections[0].page_height
    retained_section_attributes = dict(doc.sections[0]._sectPr.attrib)
    database_section = False
    for heading, content in zip(parts[1::2], parts[2::2]):
        is_database = heading == "Логическая модель базы данных"
        section_changed = is_database or database_section
        if section_changed:
            section = doc.add_section(WD_SECTION_START.NEW_PAGE)
            section.orientation = WD_ORIENT.LANDSCAPE if is_database else WD_ORIENT.PORTRAIT
            section.page_width = portrait_height if is_database else portrait_width
            section.page_height = portrait_width if is_database else portrait_height
            section.different_first_page_header_footer = False
            number = section._sectPr.find("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}pgNumType")
            if number is not None:
                number.attrib.pop("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}start", None)
        database_section = is_database
        p = doc.add_heading(heading, level=1)
        p.paragraph_format.page_break_before = not section_changed
        for piece in re.split(r"(!\[[^\]]*\]\([^)]+\))", content):
            match = re.fullmatch(r"!\[([^\]]*)\]\(([^)]+)\)", piece.strip())
            if match:
                png = assets / Path(match[2]).name
                p = doc.add_paragraph()
                p.paragraph_format.keep_with_next = False
                p.paragraph_format.first_line_indent = Mm(0)
                p.paragraph_format.space_after = Pt(9)
                p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                inline = p.add_run().add_picture(str(png), width=Mm(230 if is_database else 165))
                inline._inline.docPr.set("descr", match[1])
            elif piece.strip():
                start = len(doc.paragraphs)
                b.markdown(piece, HERE / "architecture.md", base=1 if is_database else 2)
                if is_database:
                    for added in doc.paragraphs[start:]:
                        if added.text == "Дневник и метки":
                            added.paragraph_format.page_break_before = True
    doc.sections[0]._sectPr.attrib.update(retained_section_attributes)
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = BytesIO()
    doc.save(temp)
    edited = ZipFile(BytesIO(temp.getvalue()))
    # Keep all unrelated OOXML parts byte-for-byte, including customXml and footer PAGE.
    with ZipFile(output, "w", ZIP_DEFLATED) as final:
        for name in edited.namelist():
            data = original.read(name) if name in original.namelist() and name not in EDITABLE_PARTS else edited.read(name)
            if name == "word/settings.xml":
                settings = etree.fromstring(original.read(name))
                tag = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
                flag = settings.find(tag + "updateFields")
                if flag is None:
                    flag = etree.SubElement(settings, tag + "updateFields")
                flag.set(tag + "val", "true")
                data = etree.tostring(settings, xml_declaration=True, encoding="UTF-8", standalone=True)
            final.writestr(name, data)
    print(output)


def normalized(text):
    text = re.sub(r"(?m)^\s*(?:•|-|\d+\.)\s+", "", text)
    return re.sub(r"\s+", "", text.replace("\u00ad", "").replace("**", "").replace("`", ""))


def architecture_fragments(markdown):
    """Read visible prose and table cells independently from the DOCX builder."""
    for block in re.split(r"\n\s*\n", markdown.strip()):
        block = block.strip()
        if not block:
            continue
        match = re.fullmatch(IMAGE, block)
        if match:
            continue
        elif block.startswith("|"):
            for line in block.splitlines():
                cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
                if not all(re.fullmatch(r"[:\- ]+", cell) for cell in cells):
                    yield from cells
        elif block.startswith("#"):
            yield re.sub(r"^#+\s+", "", block)
        elif re.match(r"^(?:- |\d+\. )", block):
            for item in re.split(r"\n(?=- |\d+\. )", block):
                yield re.sub(r"^(?:- |\d+\. )", "", item)
        else:
            yield block


def check(output):
    original = source_zip()
    final = ZipFile(output)
    errors = []
    for part in original.namelist():
        if part not in final.namelist():
            errors.append("Missing original part: " + part)
        elif part not in EDITABLE_PARTS and original.read(part) != final.read(part):
            errors.append("Changed preserve-only part: " + part)
    tag = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    old_settings = etree.fromstring(original.read("word/settings.xml"))
    new_settings = etree.fromstring(final.read("word/settings.xml"))
    flag = new_settings.find(tag + "updateFields")
    if flag is None or flag.get(tag + "val") != "true":
        errors.append("Missing field refresh setting")
    for settings in (old_settings, new_settings):
        for flag in settings.findall(tag + "updateFields"):
            settings.remove(flag)
    if etree.tostring(old_settings) != etree.tostring(new_settings):
        errors.append("Unexpected change outside field refresh setting")
    reference_doc = Document(REFERENCE)
    doc = Document(output)
    text = "\n".join(p.text for p in doc.paragraphs)
    text += "\n" + "\n".join(c.text for t in doc.tables for r in t.rows for c in r.cells)
    pdf = PdfReader(output.with_suffix(".pdf"))
    pdf_text = "\n".join(p.extract_text() for p in pdf.pages)
    md = (HERE / "technical-specification.md").read_text()
    arch_md = (HERE / "architecture.md").read_text().split("\n", 1)[1]
    sources = {"MD": md, "DOCX": text, "PDF": pdf_text}
    expected_md = revised(REFERENCE_MD.read_text()).rstrip() + "\n" + arch_md
    if md != expected_md:
        errors.append("MD differs from retained requirements plus current architecture")
    # Positional equality catches omitted, reordered or altered original paragraphs,
    # even when the same words appear elsewhere in the architecture addition.
    for i, paragraph in enumerate(reference_doc.paragraphs):
        expected = COVER if i == 4 else revised(paragraph.text)
        if i >= len(doc.paragraphs) or doc.paragraphs[i].text != expected:
            errors.append(f"Changed retained DOCX paragraph {i}: {paragraph.text[:70]}")
        if i >= 8 and expected.strip():
            for label, value in sources.items():
                if normalized(expected) not in normalized(value):
                    errors.append(f"Lost stage-2 paragraph in {label}: {paragraph.text[:70]}")
    for ti, table in enumerate(reference_doc.tables):
        if ti >= len(doc.tables):
            errors.append(f"Missing retained table {ti}")
            continue
        actual = doc.tables[ti]
        if len(actual.rows) != len(table.rows) or len(actual.columns) != len(table.columns):
            errors.append(f"Changed retained table dimensions: {ti}")
        for ri, row in enumerate(table.rows):
            for ci, cell in enumerate(row.cells):
                expected = revised(cell.text)
                if ri >= len(actual.rows) or ci >= len(actual.rows[ri].cells) or actual.rows[ri].cells[ci].text != expected:
                    errors.append(f"Changed retained DOCX cell {ti}/{ri}/{ci}: {expected[:70]}")
                for label, value in sources.items():
                    if normalized(expected) not in normalized(value):
                        errors.append(f"Lost stage-2 table cell in {label}: {expected[:70]}")
    records = [p.text for p in doc.paragraphs if re.match(r"^(?:SM|SB|SN)-\d{2}\.", p.text)]
    record_ids = {re.match(r"^\w+-\d{2}", value)[0] for value in records}
    if len(records) != 23 or record_ids != EXPECTED_IDS:
        errors.append("Expected exactly the 23 retained requirement records")
    for label, value in sources.items():
        if not EXPECTED_IDS.issubset(set(re.findall(r"(?:SM|SB|SN)-\d{2}",value))):
            errors.append("Missing requirement IDs: " + label)
        if re.search(r"Копирование БД|защищённая копия|внешни[ехя]+ копи|восстановление копии|Копии создаются", value, re.I):
            errors.append("Excluded database-copy material remains: " + label)
        for fragment in architecture_fragments(arch_md):
            if normalized(fragment) not in normalized(value):
                errors.append(f"Lost architecture text in {label}: {fragment[:70]}")
    for paragraph in doc.paragraphs:
        value = paragraph.text.strip()
        if value and normalized(value) not in normalized(pdf_text):
            errors.append("PDF lost paragraph: " + value[:70])
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                if normalized(cell.text) not in normalized(pdf_text): errors.append("PDF lost table cell: " + cell.text[:70])
    images = re.findall(IMAGE, arch_md)
    if len(images) != 7 or len(doc.inline_shapes) != len(images):
        errors.append("Expected seven architecture figures from architecture.md")
    for shape, (caption, _) in zip(doc.inline_shapes, images):
        if shape._inline.docPr.get("descr") != caption:
            errors.append("Figure order or alternative text differs from architecture.md")
    if re.search(r"https?://|Источники|sequence|as[ -]is|to[ -]be", arch_md, re.I):
        errors.append("Architecture contains excluded sources or process/sequence material")
    if doc.element.xpath(".//w:ins | .//w:del | .//w:pBdr"): errors.append("Unexpected revisions or paragraph borders")
    if len(doc.sections) != 3:
        errors.append("Expected portrait / landscape / portrait sections")
    else:
        reference_section = reference_doc.sections[0]
        for index, section in enumerate(doc.sections):
            expected = ((reference_section.page_height, reference_section.page_width)
                        if index == 1 else (reference_section.page_width, reference_section.page_height))
            if (section.page_width, section.page_height) != expected:
                errors.append(f"Unexpected page dimensions in section {index}")
            for margin in ("top_margin", "bottom_margin", "left_margin", "right_margin"):
                if getattr(section, margin) != getattr(reference_section, margin):
                    errors.append(f"Changed retained {margin} in section {index}")
        if etree.tostring(doc.sections[0]._sectPr) != etree.tostring(reference_section._sectPr):
            errors.append("Changed retained first section")
    if any(re.match(r"^Рисунок \d+", p.text) for p in doc.paragraphs):
        errors.append("Visible figure captions remain")
    print(json.dumps({
        "pages": len(pdf.pages), "figures": len(doc.inline_shapes),
        "requirements": len(records), "retained_paragraphs": len(reference_doc.paragraphs),
        "retained_table_cells": sum(len(r.cells) for t in reference_doc.tables for r in t.rows),
        "architecture_sections": len(re.findall(r"^## (.+)$", arch_md, re.M)),
        "errors": errors,
    },ensure_ascii=False,indent=2))
    if errors: raise SystemExit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--out",type=Path,default=HERE / "Calorica-stage-3-technical-specification.docx")
    parser.add_argument("--assets",type=Path,default=HERE / "assets")
    parser.add_argument("--check",action="store_true")
    args = parser.parse_args()
    if args.check:
        check(args.out)
    else:
        sync_markdown()
        build(args.out,args.assets)
