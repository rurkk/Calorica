// Run a task-local copy with the bundled runtime and node_modules link.
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {PresentationFile, FileBlob} from '@oai/artifact-tool';

const HERE=path.dirname(fileURLToPath(import.meta.url));
const ROOT=process.env.CALORICA_ROOT ?? path.resolve(HERE,'../../..');
const workspace=process.env.CALORICA_ARTIFACT_WORKSPACE;
const skill=process.env.CALORICA_PRESENTATION_SKILL;
const python=process.env.CALORICA_RUNTIME_PYTHON;
if(!workspace || !skill || !python) throw Error('Set artifact workspace, presentation skill and runtime Python paths');
const out=path.join(workspace,process.env.CALORICA_OUTPUT_DIR ?? 'output');
await fs.mkdir(out,{recursive:true});
await fs.mkdir(path.join(workspace,'build'),{recursive:true});
const source=path.join(ROOT,'docs/semester-mvp/stage-2/Calorica-stage-2.pptx');
const p=await PresentationFile.importPptx(await FileBlob.load(source));
const baseline=await p.inspect({select:'$.slides[*]',include:['id','index','elements.id','elements.text','elements.kind','elements.position']});
await fs.writeFile(path.join(workspace,'build/source-inspection.ndjson'),baseline.ndjson);
// Reuse the source layouts and duplicate the database layout for its second view.
p.slides.keep([0,1,2,3,4,5,6,7]);
p.slides.items[5].duplicate().moveTo(6);
const initial=await p.inspect({select:'$.slides[*]',include:['id','index','elements.id','elements.text','elements.kind','elements.position']});
const C={bg:'#F7F3EA',ink:'#17352B',dark:'#153A30',muted:'#5E7066',sage:'#809F87',accent:'#DF825E',pale:'#DDE6D7',role:'#EAE6DC',white:'#F7F3EA'};
const text=(s,v,x,y,w,h,size=24,bold=false,color=C.ink)=>{
  const sh=s.shapes.add({name:v.replaceAll('\n',' ').slice(0,70),geometry:'textbox',position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
  sh.text=v;sh.text.style={typeface:'Helvetica Neue',fontSize:size,bold,color,autoFit:'none'};sh.text.insets=0;return sh;
};
function wipe(i,title,subtitle){
  const s=p.slides.items[i],r=initial.records[i];
  const heading=r.elements.find(e=>e.kind==='textbox' && e.position?.top>=60 && e.position?.top<120);
  const footer=r.elements.find(e=>/^\d{2}$/.test(e.text??'') && e.position?.top>650);
  const kept=new Set([heading?.id,footer?.id,...r.elements.filter(e=>e.kind==='shape' && e.position?.top<60).map(e=>e.id)]);
  p.delete(r.elements.filter(e=>!kept.has(e.id)).map(e=>e.id));
  if(!heading || !footer)throw Error(`Missing template furniture on slide ${i+1}`);
  p.resolve(heading.id).text=title;p.resolve(footer.id).text=String(i+1).padStart(2,'0');
  text(s,subtitle,72,137,1136,54,21,false,i===8?'#C7D5C8':C.muted);return s;
}

// draw.io remains the authoritative editable diagram source.
async function diagram(slide, name, top=205, height=440, left=72, width=1136){
  const png=path.join(ROOT,'docs/semester-mvp/stage-3/assets',`${name}.drawio.png`);
  const bytes=await fs.readFile(png);
  slide.images.add({blob:bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength),contentType:'image/png',alt:`Схема ${name}; редактируемый исходник: diagrams/${name}.drawio`,fit:'contain',position:{left,top,width,height}});
}

// Cover art and typography remain imported from the previous stage.
const cover=p.slides.items[0].shapes.items.find(z=>z.text.toString()==='Этап 2 · требования и план семестра');
if(!cover)throw Error('Missing retained cover subtitle');cover.text='Этап 3 · архитектура решения';
let s=wipe(1,'Архитектура и технологии','Android-клиент, модульный монолит и PostgreSQL');
const table=s.tables.add({rows:6,columns:2,left:72,top:215,width:635,height:326,columnWidths:[205,430],values:[['Область','Технологии'],['Android','Kotlin, Compose, Material 3'],['Состояние и сеть','ViewModel, StateFlow, Hilt\nRetrofit, OkHttp'],['Сервер','Kotlin, Spring Boot, Security'],['Данные','PostgreSQL, Spring JDBC'],['Миграции','Liquibase и SQL']]});
table.borders.assign({style:'solid',fill:C.sage,width:1});
for(let r=0;r<6;r++)for(let c=0;c<2;c++){let cell=table.getCell(r,c);cell.fill=r===0?C.pale:C.bg;cell.text.style={typeface:'Helvetica Neue',fontSize:r===0?24:23,bold:r===0||c===0,color:C.ink};}
text(s,'Модульный монолит',760,215,445,44,29,true,C.accent);
text(s,'Один процесс и одна БД.\nСвязанные изменения —\nв одной транзакции.',760,282,445,115,26);
text(s,'Внутренние вызовы\nвыполняются в Kotlin.',760,435,445,76,25);
text(s,'Сервер проверяет права и рассчитывает КБЖУ; клиент показывает результат.',72,590,1136,62,25,true);
s=wipe(2,'Контекст системы','C4 Context · две роли и граница программной системы');await diagram(s,'context');
s=wipe(3,'Контейнеры системы','C4 Container · приложения, хранилище и способы взаимодействия');await diagram(s,'containers');
s=wipe(4,'Компоненты сервера','C4 Component · вызовы Android, предметные модули и PostgreSQL');await diagram(s,'server-components',190,480,30,1220);
s=wipe(5,'БД: аккаунты и каталог','Аккаунты, сессии, продукты, составы и единицы измерения');await diagram(s,'data-model',190,480,30,1220);
s=wipe(6,'БД: дневник и метки','Записи питания, справочники меток и таблицы связей');await diagram(s,'data-model-diary',190,480,30,1220);
s=wipe(7,'Устройство Android клиента','UI, состояние и слой данных · два пользовательских раздела');await diagram(s,'android');
s=wipe(8,'Выпуск и развёртывание','Целевой CI/CD · независимые выпуски Android и сервера');await diagram(s,'deployment',195,465,45,1190);
const speech=await fs.readFile(path.join(ROOT,'docs/semester-mvp/stage-3/defense-notes.md'),'utf8');
const sections=[...speech.matchAll(/^## Слайд \d+\. (.+)\n\n([\s\S]*?)(?=\n## |(?![\s\S]))/gm)];
if(sections.length!==9)throw Error('Expected nine speech sections');
// Speaker notes contain only the shortened connected speech, without references.
for(let i=0;i<9;i++)p.slides.items[i].speakerNotes.text=sections[i][2].trim().replace(/\s+/g,' ');
for(let i=0;i<9;i++)await fs.writeFile(path.join(out,`slide-${i+1}.png`),new Uint8Array(await (await p.slides.items[i].export({format:'png',scale:1.5})).arrayBuffer()));
const finalizer=await import(pathToFileURL(path.join(skill,'container_tools/artifact_tool_utils.mjs')));
const candidate=path.join(workspace,'build',`${path.basename(out)}-candidate.pptx`);
await (await PresentationFile.exportPptx(p)).save(candidate);
await finalizer.finalizePresentation({workspaceDir:workspace,candidatePath:candidate,finalPath:path.join(out,'Calorica-stage-3.pptx'),pythonExecutable:python,
  integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),
  explicitTotalSlideCount:9,requiredNativeTableOwnerSlides:[2],layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-heading-fit','--require-native-table-slide','2'],
  fontPolicy:{basis:'reference',families:['Helvetica Neue'],referencePath:source,referenceSha256:crypto.createHash('sha256').update(await fs.readFile(source)).digest('hex')},verifyArtifactToolImport:true,
  receiptPath:path.join(workspace,'build',`${path.basename(out)}-validation.json`)});
console.log('Nine slides with seven architecture figures ready:',out);
