"""Shared formatting for the semester MVP document builders."""
import re

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_TAB_ALIGNMENT, WD_TAB_LEADER
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Mm, Pt, RGBColor

def element(tag, **attrs):
    el = OxmlElement(tag)
    for key, value in attrs.items():
        el.set(qn("w:" + key), str(value))
    return el

def heading_text(s):
    s = re.sub(r"^\d+\.\s*", "", s)
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]", " ", s)).strip()

def field(p, instruction, display):
    p.add_run()._r.append(element("w:fldChar", fldCharType="begin"))
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = " " + instruction + " "
    p.add_run()._r.append(instr)
    p.add_run()._r.append(element("w:fldChar", fldCharType="separate"))
    p.add_run(display)
    p.add_run()._r.append(element("w:fldChar", fldCharType="end"))

class Builder:
    def __init__(self):
        self.doc = Document()
        self.sources = {}
        self.bibliography = {}
        self.links = {}
        self.setup()

    def setup(self):
        doc = self.doc
        sec = doc.sections[0]
        sec.page_width, sec.page_height = Mm(210), Mm(297)
        sec.left_margin, sec.right_margin = Mm(30), Mm(15)
        sec.top_margin = sec.bottom_margin = Mm(20)
        sec.footer_distance = Mm(10)
        sec.different_first_page_header_footer = True
        normal = doc.styles["Normal"]
        normal.font.name, normal.font.size = "Times New Roman", Pt(12)
        normal.font.color.rgb = RGBColor(0, 0, 0)
        normal.paragraph_format.line_spacing = 1.15
        normal.paragraph_format.space_after = Pt(5)
        normal.paragraph_format.widow_control = True
        normal.element.get_or_add_rPr().append(element("w:lang", val="ru-RU"))
        for name, size in [("Title", 22), ("Subtitle", 16), ("Heading 1", 16), ("Heading 2", 14), ("Heading 3", 12), ("Heading 4", 12)]:
            style = doc.styles[name]
            style.font.name, style.font.size = "Times New Roman", Pt(size)
            style.font.color.rgb = RGBColor(0, 0, 0)
            style.font.bold = name != "Subtitle"
            style.paragraph_format.line_spacing = 1.15
            style.paragraph_format.space_before = Pt(14)
            style.paragraph_format.space_after = Pt(7)
            style.paragraph_format.keep_with_next = True
        for style in doc.styles:
            for border in style.element.xpath(".//w:pBdr"):
                border.getparent().remove(border)
            for spacing in style.element.xpath(".//w:contextualSpacing"):
                spacing.getparent().remove(spacing)
            for fonts in style.element.xpath(".//w:rFonts"):
                for attr in list(fonts.attrib):
                    if "Theme" in attr: del fonts.attrib[attr]
                for attr in ("ascii", "hAnsi", "eastAsia", "cs"):
                    fonts.set(qn("w:" + attr), "Times New Roman")
        numbering = doc.part.numbering_part.element
        abstract = element("w:abstractNum", abstractNumId=80)
        abstract.append(element("w:multiLevelType", val="multilevel"))
        for level in range(2):
            lvl = element("w:lvl", ilvl=level)
            for el in [element("w:start", val=1), element("w:numFmt", val="decimal"),
                       element("w:pStyle", val=f"Heading{level+1}"),
                       element("w:lvlText", val="%1" if level == 0 else "%1.%2"),
                       element("w:suff", val="space")]:
                lvl.append(el)
            abstract.append(lvl)
        numbering.append(abstract)
        num = element("w:num", numId=80)
        num.append(element("w:abstractNumId", val=80))
        numbering.append(num)
        for level in range(2):
            numpr = element("w:numPr")
            numpr.append(element("w:ilvl", val=level))
            numpr.append(element("w:numId", val=80))
            doc.styles[f"Heading {level+1}"].element.get_or_add_pPr().append(numpr)
        footer = sec.footer.paragraphs[0]
        footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
        field(footer, "PAGE", "1")
        for run in footer.runs:
            run.font.size = Pt(11)
        doc.core_properties.title = "Техническое задание Calorica"
        doc.core_properties.author = "Команда Calorica"
        doc.core_properties.subject = "Второй этап учебного проекта"
        doc.core_properties.keywords = "Calorica, техническое задание, этап 2"

    def inline(self, p, text, source):
        def link(m):
            label, target = m.group(1), m.group(2)
            if target.startswith("http"):
                if target not in self.bibliography:
                    self.bibliography[target] = (len(self.bibliography) + 1, label)
                return f"{label} [{self.bibliography[target][0]}]"
            ref = self.links.get(str((source.parent / target.split("#")[0]).resolve()))
            if not ref:
                raise ValueError(f"Unmapped link {source}: {target}")
            return f"{label} (раздел {ref})"
        text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link, text)
        for frag in re.split(r"(\*\*.*?\*\*|`[^`]+`)", text):
            if frag.startswith("**"):
                p.add_run(frag[2:-2]).bold = True
            elif frag.startswith("`"):
                run = p.add_run(frag[1:-1])
                run.font.name = "Consolas"
                run.font.size = Pt(11)
            else:
                p.add_run(frag)

    def table(self, rows, source):
        # Long requirement and acceptance prose reads better as numbered records.
        if rows[0][0] in ("ID", "ID проверки"):
            for row in rows[1:]:
                p = self.doc.add_paragraph()
                p.paragraph_format.keep_with_next = True
                p.add_run(row[0]).bold = True
                if row[0].startswith("AC-"):
                    p.add_run(" · " + row[1])
                    body = row[2]
                    self.inline(self.doc.add_paragraph(), body, source)
                else:
                    self.inline(p, ". " + row[1], source)
                    p = self.doc.add_paragraph()
                    p.add_run("Проверка. ").italic = True
                    self.inline(p, row[2], source)
            return
        t = self.doc.add_table(rows=0, cols=len(rows[0]))
        t.alignment = WD_TABLE_ALIGNMENT.CENTER
        t.autofit = False
        if rows[0][0] == "Направление": widths = [30, 67, 68]
        elif len(rows[0]) == 3: widths = [43, 68, 54]
        elif rows[0][0] == "Группа": widths = [57, 108]
        else: widths = [64, 101]
        for col, width in zip(t.columns, widths): col.width = Mm(width)
        borders = element("w:tblBorders")
        for side in ["top", "left", "bottom", "right", "insideH", "insideV"]:
            borders.append(element("w:" + side, val="single", sz=4, color="D9D9D9"))
        t._tbl.tblPr.append(borders)
        for i, values in enumerate(rows):
            row = t.add_row()
            row._tr.get_or_add_trPr().append(element("w:cantSplit"))
            if i == 0: row._tr.get_or_add_trPr().append(element("w:tblHeader"))
            for cell, value, width in zip(row.cells, values, widths):
                cell.width = Mm(width)
                cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
                pr = cell._tc.get_or_add_tcPr()
                margins = element("w:tcMar")
                for side in ["top", "left", "bottom", "right"]:
                    margins.append(element("w:" + side, w=90, type="dxa"))
                pr.append(margins)
                if i == 0: pr.append(element("w:shd", fill="E8EDF2"))
                p = cell.paragraphs[0]
                p.paragraph_format.line_spacing = 1.1
                p.paragraph_format.space_after = Pt(3)
                p.paragraph_format.space_before = Pt(3)
                self.inline(p, value, source)
                for run in p.runs:
                    run.font.size = Pt(11)
                    if i == 0: run.bold = True
        self.doc.add_paragraph().paragraph_format.space_after = Pt(0)

    def markdown(self, text, source, base=3):
        lines = text.strip().splitlines()
        i = 0
        while i < len(lines):
            line = lines[i].strip()
            if not line:
                i += 1; continue
            if line.startswith("```"):
                i += 1
                while i < len(lines) and not lines[i].startswith("```"):
                    p = self.doc.add_paragraph()
                    p.paragraph_format.line_spacing = 1.1
                    p.paragraph_format.space_after = Pt(3)
                    run = p.add_run(lines[i])
                    run.font.name, run.font.size = "Consolas", Pt(10)
                    i += 1
                i += 1; continue
            if line.startswith("|"):
                rows = []
                while i < len(lines) and lines[i].strip().startswith("|"):
                    vals = [x.strip() for x in lines[i].strip().strip("|").split("|")]
                    if not all(re.fullmatch(r"[:\- ]+", x) for x in vals): rows.append(vals)
                    i += 1
                self.table(rows, source); continue
            match = re.match(r"^(#+)\s+(.*)", line)
            if match:
                level = min(4, base + len(match[1]) - 2)
                self.doc.add_heading(heading_text(match[2]), level=level)
                i += 1; continue
            item = re.match(r"^(- |\d+\. )(.*)", line)
            content = item[2] if item else line
            prefix = item[1] if item else ""
            i += 1
            while i < len(lines) and lines[i].strip() and not re.match(r"^\s*(#|\||```|- |\d+\. )", lines[i]):
                content += " " + lines[i].strip(); i += 1
            p = self.doc.add_paragraph()
            if item:
                p.paragraph_format.left_indent = Mm(5)
                p.paragraph_format.first_line_indent = Mm(-5)
                p.add_run("• " if prefix == "- " else prefix)
            self.inline(p, content, source)
