"""Build only the two native draw.io ER views; other diagrams are untouched.

Table and field names describe the proposed relational model.  This generator
has no dependency on build_diagrams.py and can be run without rebuilding the
user-edited architecture diagrams.
"""
from argparse import ArgumentParser
from html import escape
from pathlib import Path
import re
import xml.etree.ElementTree as ET


HERE = Path(__file__).resolve().parent
HEADER = 42
ROW = 30


class ERView:
    def __init__(self, name, title, width, height):
        self.name = name
        self.xml = ET.Element("mxfile", host="app.diagrams.net", compressed="false")
        page = ET.SubElement(self.xml, "diagram", id=name, name=title)
        model = ET.SubElement(page, "mxGraphModel", dx=str(width), dy=str(height),
                              grid="1", gridSize="10", guides="1", tooltips="1",
                              connect="1", arrows="1", fold="1", page="0",
                              pageScale="1", pageWidth=str(width),
                              pageHeight=str(height), background="#FFFFFF",
                              adaptiveColors="auto", math="0", shadow="0")
        self.root = ET.SubElement(model, "root")
        ET.SubElement(self.root, "mxCell", id="0")
        ET.SubElement(self.root, "mxCell", id="1", parent="0")
        self.edge_count = 0

    def vertex(self, key, value, x, y, width, height, style, parent="1"):
        cell = ET.SubElement(self.root, "mxCell", id=key, value=value,
                             style=style, vertex="1", parent=parent)
        ET.SubElement(cell, "mxGeometry", x=str(x), y=str(y), width=str(width),
                      height=str(height), **{"as": "geometry"})
        return key

    def table(self, name, fields, x, y, width=400, field_width=170):
        header_style = (
            "swimlane;html=1;horizontal=1;startSize=42;rounded=0;"
            "fillColor=#E2E8F0;swimlaneFillColor=#FFFFFF;strokeColor=#64748B;"
            "strokeWidth=1.5;align=left;spacingLeft=12;verticalAlign=middle;"
            "fontFamily=Arial;fontColor=#334155;fontSize=24;fontStyle=1;"
            "container=1;collapsible=0;pointerEvents=0;"
        )
        self.vertex(name, name, x, y, width, HEADER + ROW * len(fields), header_style)
        row_style = (
            "html=1;fillColor=#FFFFFF;strokeColor=#CBD5E1;strokeWidth=0.5;"
            "container=1;pointerEvents=0;rounded=0;"
        )
        text_style = (
            "text;html=1;fillColor=none;strokeColor=none;align=left;"
            "verticalAlign=middle;spacing=0;fontFamily=Arial;fontColor=#334155;"
            "whiteSpace=nowrap;overflow=hidden;"
        )
        for index, (field, display, key_info) in enumerate(fields):
            row_id = f"{name}.{field}"
            self.vertex(row_id, "", 0, HEADER + ROW * index, width, ROW,
                        row_style, parent=name)
            self.vertex(f"{row_id}.name", escape(display), 12, 0,
                        field_width - 16, ROW,
                        text_style + "fontSize=23;", parent=row_id)
            self.vertex(f"{row_id}.key", escape(key_info), field_width, 0,
                        width - field_width - 10, ROW,
                        text_style + "fontSize=22;", parent=row_id)

    def relation(self, source, target, cardinality="1:N", offset=0,
                 optional_parent=False):
        self.edge_count += 1
        style = (
            "edgeStyle=entityRelationEdgeStyle;segment=20;html=1;rounded=0;"
            "strokeColor=#64748B;strokeWidth=1.8;fontColor=#475569;"
            "fontFamily=Arial;fontSize=21;labelBackgroundColor=#FFFFFF;"
            "defaultLabelBackgroundColor=page;jumpStyle=arc;jumpSize=6;"
            f"startArrow={'ERzeroToOne' if optional_parent else 'ERmandOne'};"
            "endArrow=ERzeroToMany;startFill=0;endFill=0;startSize=12;endSize=14;"
        )
        edge = ET.SubElement(self.root, "mxCell", id=f"relation-{self.edge_count}",
                             value=cardinality, style=style, edge="1", parent="1",
                             source=source, target=target)
        geometry = ET.SubElement(edge, "mxGeometry", relative="1", **{"as": "geometry"})
        if offset:
            ET.SubElement(geometry, "mxPoint", x="0", y=str(offset), **{"as": "offset"})

    def save(self, output_dir):
        xml = ET.tostring(self.xml, encoding="unicode")
        match = re.search(r"<mxfile[\s\S]*?</mxfile>|<mxGraphModel[\s\S]*?</mxGraphModel>", xml)
        if match and xml.strip() != match.group(0):
            xml = match.group(0)
        xml = re.sub(r'="([^"]*)"', lambda m: '="' + re.sub(
            r'&(?!(?:amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);)',
            '&amp;', m[1].replace('<', '&lt;')) + '"', xml)
        xml = re.sub(r'<mxCell id="([^"]+)" parent="\1"\s*/>',
                     r'<mxCell id="\1" parent="0"/>', xml)
        ET.fromstring(xml)
        output_dir.mkdir(parents=True, exist_ok=True)
        target = output_dir / f"{self.name}.drawio"
        target.write_text(xml + "\n")
        print(target)


def build_data_diagrams(output_dir):
    d = ERView("data-model", "ER · аккаунты и продукты", 1470, 620)
    d.table("users", [
        ("id", "id", "PK"),
        ("email", "email", "UNIQUE"),
        ("password_hash", "password_hash", ""),
        ("role", "role", ""),
    ], 0, 0, field_width=190)
    d.table("sessions", [
        ("id", "id", "PK"),
        ("user_id", "user_id", "FK <users.id>"),
        ("created_at", "created_at", ""),
        ("expires_at", "expires_at", ""),
        ("revoked_at", "revoked_at NULL", ""),
    ], 520, 0, field_width=210)
    d.table("refresh_tokens", [
        ("id", "id", "PK"),
        ("session_id", "session_id", "FK <sessions.id>"),
        ("token_hash", "token_hash", "UNIQUE"),
        ("expires_at", "expires_at", ""),
        ("used_at", "used_at NULL", ""),
        ("revoked_at", "revoked_at NULL", ""),
    ], 1040, 0, width=430, field_width=210)
    d.table("products", [
        ("id", "id", "PK"),
        ("owner_id", "owner_id NULL", "FK <users.id>"),
        ("name", "name", ""),
        ("kcal", "kcal", ""),
        ("protein", "protein", ""),
        ("fat", "fat", ""),
        ("carbs", "carbs", ""),
        ("common_yield", "common_yield NULL", ""),
        ("deleted_at", "deleted_at NULL", ""),
    ], 0, 280, field_width=245)
    d.table("product_components", [
        ("parent_id", "parent_id", "PK, FK <products.id>"),
        ("child_id", "child_id", "PK, FK <products.id>"),
        ("share", "share", ""),
        ("yield", "yield NULL", ""),
    ], 520, 280, field_width=160)
    d.table("product_units", [
        ("product_id", "product_id", "PK, FK <products.id>"),
        ("unit_id", "unit_id", "PK, FK <units.id>"),
        ("grams_per_unit", "grams_per_unit", ""),
    ], 520, 480, width=430, field_width=190)
    d.table("units", [
        ("id", "id", "PK"),
        ("code", "code", "UNIQUE"),
        ("name", "name", ""),
    ], 1040, 480, width=430, field_width=190)
    d.relation("users.id", "sessions.user_id", offset=-15)
    d.relation("sessions.id", "refresh_tokens.session_id", offset=-15)
    d.relation("users.id", "products.owner_id", "0..1:N", optional_parent=True)
    d.relation("products.id", "product_components.parent_id", offset=-15)
    d.relation("products.id", "product_components.child_id", offset=15)
    d.relation("products.id", "product_units.product_id")
    d.relation("units.id", "product_units.unit_id", offset=15)
    d.save(output_dir)

    d = ERView("data-model-diary", "ER · дневник и лейблы", 1290, 550)
    d.table("product_labels", [
        ("id", "id", "PK"),
        ("name", "name", "UNIQUE"),
    ], 0, 0, width=430, field_width=150)
    d.table("product_label_links", [
        ("product_id", "product_id", "PK, FK <products.id>"),
        ("label_id", "label_id", "PK, FK <product_labels.id>"),
    ], 820, 0, width=430, field_width=150)
    d.table("diary_entries", [
        ("id", "id", "PK"),
        ("owner_id", "owner_id", "FK <users.id>"),
        ("product_id", "product_id", "FK <products.id>"),
        ("consumed_at", "consumed_at UTC", ""),
        ("grams", "grams", ""),
    ], 0, 280, width=430, field_width=225)
    d.table("meal_labels", [
        ("id", "id", "PK"),
        ("code", "code", "UNIQUE"),
        ("name", "name", ""),
    ], 820, 220, width=430, field_width=150)
    d.table("diary_entry_labels", [
        ("entry_id", "entry_id", "PK, FK <diary_entries.id>"),
        ("label_id", "label_id", "PK, FK <meal_labels.id>"),
    ], 820, 430, width=430, field_width=150)
    d.relation("product_labels.id", "product_label_links.label_id", offset=-15)
    d.relation("diary_entries.id", "diary_entry_labels.entry_id")
    d.relation("meal_labels.id", "diary_entry_labels.label_id")
    d.save(output_dir)


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=HERE / "diagrams")
    build_data_diagrams(parser.parse_args().output_dir)
