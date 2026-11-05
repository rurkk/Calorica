"""Create the stage-4 report using the retained stage-2 Word styles and cover.
Run with Codex bundled Python. The renderer is a separate mandatory step.
"""
import argparse,hashlib,re,sys
from pathlib import Path
from copy import deepcopy
from zipfile import ZipFile
from docx import Document
from docx.shared import Mm,Pt
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT,WD_CELL_VERTICAL_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.opc.constants import RELATIONSHIP_TYPE as RT
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
REFERENCE=HERE.parent/'stage-2/Calorica-semester-MVP-TZ.docx'
EXPECTED='d7ade17b96562c0e3c3f32556346228019f9ccdb70dabaf0a725cbff4fca0ceb'
def el(name,**attrs):
 x=OxmlElement('w:'+name)
 for k,v in attrs.items():x.set(qn('w:'+k),str(v))
 return x

def inline(p,text):
 for frag in re.split(r'(\[[^\]]+\]\([^)]+\)|\*\*.*?\*\*|`[^`]+`)',text):
  link=re.fullmatch(r'\[([^\]]+)\]\(([^)]+)\)',frag)
  if link:
   label,target=link.groups()
   if not target.startswith('https://'):
    file,sep,anchor=target.partition('#')
    rel=(HERE/file).resolve().relative_to(ROOT).as_posix()
    target='https://github.com/rurkk/Calorica/blob/codex/stage4-materials-01a1a741/'+rel+(sep+anchor if sep else '')
   h=OxmlElement('w:hyperlink');h.set(qn('r:id'),p.part.relate_to(target,RT.HYPERLINK,is_external=True))
   r=OxmlElement('w:r');pr=el('rPr');pr.append(el('color',val='0563C1'));pr.append(el('u',val='single'));r.append(pr);t=el('t');t.text=label;r.append(t);h.append(r);p._p.append(h);continue
  r=p.add_run(frag[2:-2] if frag.startswith('**') else frag.strip('`'))
  if frag.startswith('**'):r.bold=True

def table(doc,rows):
 n=len(rows[0]); widths=([20,76,69] if n==3 else [50,115])
 t=doc.add_table(rows=0,cols=n);t.autofit=False;t.alignment=WD_TABLE_ALIGNMENT.CENTER
 for c,w in zip(t.columns,widths):c.width=Mm(w)
 borders=el('tblBorders')
 for side in ['top','left','bottom','right','insideH','insideV']:borders.append(el(side,val='single',sz=4,color='D9D9D9'))
 t._tbl.tblPr.append(borders)
 for idx,values in enumerate(rows):
  row=t.add_row();tr=row._tr.get_or_add_trPr();tr.append(el('cantSplit'))
  if idx==0:tr.append(el('tblHeader'))
  for c,value,w in zip(row.cells,values,widths):
   c.width=Mm(w);c.vertical_alignment=WD_CELL_VERTICAL_ALIGNMENT.CENTER
   pr=c._tc.get_or_add_tcPr();m=el('tcMar')
   for side in ['top','left','bottom','right']:m.append(el(side,w=90,type='dxa'))
   pr.append(m)
   if idx==0:pr.append(el('shd',fill='E8EDF2'))
   p=c.paragraphs[0];p.paragraph_format.line_spacing=1.05;p.paragraph_format.space_after=Pt(3);p.paragraph_format.space_before=Pt(3);inline(p,value)
   for r in p.runs:r.font.size=Pt(10.5);r.bold=idx==0
 doc.add_paragraph().paragraph_format.space_after=Pt(2)

def body(doc,content):
 lines=content.splitlines();i=0;first=True
 while i<len(lines):
  l=lines[i].strip();i+=1
  if not l or l.startswith('# '):continue
  if l.startswith('## '):
   title=re.sub(r'^\d+\.\s*','',l[3:]);p=doc.add_heading(title,1)
   # Reference places each top-level section on a new page.
   p.paragraph_format.page_break_before=True;first=False;continue
  if l.startswith('### '):doc.add_heading(re.sub(r'^\d+(?:\.\d+)*\.\s*','',l[4:]),2);continue
  if l.startswith('|'):
   rows=[]
   while True:
    v=[x.strip() for x in l.strip('|').split('|')]
    if not all(re.fullmatch(r'[-: ]+',x) for x in v):rows.append(v)
    if i>=len(lines) or not lines[i].strip().startswith('|'):break
    l=lines[i].strip();i+=1
   table(doc,rows);continue
  if l.startswith('!['):
   m=re.match(r'!\[([^\]]*)\]\(([^)]+)\)',l)
   if not m:raise ValueError(l)
   p=doc.add_paragraph();p.alignment=WD_ALIGN_PARAGRAPH.CENTER;p.add_run().add_picture(str(HERE/m[2]),height=Mm(135));p=doc.add_paragraph(m[1]);p.alignment=WD_ALIGN_PARAGRAPH.CENTER
   for r in p.runs:r.font.size=Pt(10)
   continue
  while i<len(lines) and lines[i].strip() and not re.match(r'^\s*(#|\||!\[|- |\d+\. )',lines[i]):l+=' '+lines[i].strip();i+=1
  p=doc.add_paragraph()
  if l.startswith('- '):p.paragraph_format.left_indent=Mm(5);p.paragraph_format.first_line_indent=Mm(-5);l='• '+l[2:]
  inline(p,l)

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--out',type=Path,required=True);args=ap.parse_args()
 if hashlib.sha256(REFERENCE.read_bytes()).hexdigest()!=EXPECTED:raise ValueError('Reference changed; inspect it first')
 d=Document(REFERENCE)
 ps=d.paragraphs;ps[1].runs[0].text='Отчёт о реализации';ps[4].runs[0].text='Семестровый MVP\nЧетвёртый этап учебного проекта\n4 ноября 2026 года'
 for r in ps[4].runs[1:]:r.text=''
 start=next(p._p for p in d.paragraphs if p.style.name.startswith('Heading'))
 remove=False
 for x in list(d._element.body):
  if x is start:remove=True
  if remove and x.tag!=qn('w:sectPr'):d._element.body.remove(x)
 content=(HERE/'implementation-report.md').read_text();content=content[content.index('## '):];body(d,content)
 d.core_properties.title='Calorica Реализация и проверка MVP';d.core_properties.subject='Четвёртый этап учебного проекта';d.core_properties.keywords='Calorica, stage-4, MVP';d.core_properties.author='Команда Calorica'
 upd=d.settings.element.find(qn('w:updateFields'))
 if upd is None:upd=el('updateFields',val='true');d.settings.element.append(upd)
 else:upd.set(qn('w:val'),'true')
 args.out.parent.mkdir(parents=True,exist_ok=True);d.save(args.out)
 with ZipFile(REFERENCE) as a,ZipFile(args.out) as b:
  for name in ['word/styles.xml','word/numbering.xml','word/footer1.xml','word/theme/theme1.xml']:
   if a.read(name)!=b.read(name):raise AssertionError('Template part changed: '+name)
 print(args.out)
if __name__=='__main__':main()
