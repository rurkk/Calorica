// Run a task-local copy with the bundled Node runtime and a node_modules link.
// Output and validation files belong to a private directory.
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {Presentation, PresentationFile, FileBlob} from '@oai/artifact-tool';

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
const initial=await p.inspect({select:'$.slides[*]',include:['id','index','elements.id','elements.text','elements.kind','elements.position']});
await fs.writeFile(path.join(workspace,'build/source-inspection.ndjson'),initial.ndjson);
const slides=initial.records;
const C={bg:'#F7F3EA',ink:'#17352B',dark:'#153A30',muted:'#5E7066',sage:'#809F87',accent:'#DF825E',pale:'#DDE6D7'};
const shape=(s,text)=>s.shapes.items.find(x=>x.text.toString()===text);
function replace(i,old,value){const sh=shape(p.slides.items[i],old); if(!sh)throw Error(`Missing ${old}`);sh.text=value;return sh;}
function text(s,value,x,y,w,h,size=23,bold=false,color=C.ink){
  const sh=s.shapes.add({name:value.slice(0,45),geometry:'textbox',position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
  sh.text=value; sh.text.style={typeface:'Helvetica Neue',fontSize:size,bold,color,autoFit:'none'};sh.text.insets=0;return sh;
}
function box(s,value,x,y,w,h,fill=C.pale,size=25){
  const sh=s.shapes.add({name:value.replaceAll('\n',' '),geometry:'rect',position:{left:x,top:y,width:w,height:h},fill,line:{fill:C.sage,width:1.5}});
  sh.text=value;sh.text.style={typeface:'Helvetica Neue',fontSize:size,bold:true,color:C.ink,autoFit:'none'};sh.text.insets=16;return sh;
}
function connect(s,a,b,from='right',to='left'){
  return s.shapes.connect(a,b,{kind:'straight',fromSide:from,toSide:to,line:{style:'solid',fill:C.sage,width:2},tail:{type:'triangle',width:'sm',length:'sm'}});
}
function wipe(i,title,subtitle){
  const s=p.slides.items[i],record=slides[i];
  const keep=record.elements.filter(e=>e.text===record.elements.find(x=>x.kind==='textbox')?.text || e.text===String(i+1).padStart(2,'0') || (e.kind==='shape' && e.position.top<60));
  const ids=new Set(keep.map(e=>e.id));
  p.delete(record.elements.filter(e=>!ids.has(e.id)).map(e=>e.id));
  const old=keep.find(e=>e.kind==='textbox' && e.text!==String(i+1).padStart(2,'0'));
  p.resolve(old.id).text=title;
  text(s,subtitle,81,137,1115,40,21,false,C.muted);
  return s;
}

replace(0,'Этап 2 · требования и план семестра','Этап 3 · архитектура решения');
replace(1,'Объём семестровой версии','Технологии решения');
replace(1,'Два раздела Android: «Продукты» и «Дневник»','Один репозиторий, независимые сборки и выпуски');
const pairs=[['Аккаунт','Android'],['Email и пароль','Kotlin / Compose'],['Каталог','Сервер'],['Около 50 продуктов','Kotlin / Spring Boot'],['Личный продукт','База данных'],['Создание и правка','PostgreSQL'],['Состав блюда','Доступ к данным'],['Доли и выход','Spring JDBC / SQL'],['Запись питания','Миграции'],['Граммы и время','Liquibase'],['Итог дня','Выпуск'],['Калории и БЖУ','Actions / Docker'],['Серверная часть','Серверная архитектура'],['Правила и расчёты','Один сервер'],['Вложенные составы, выход блюда, права доступа и мягкое удаление.','Модульный монолит.\nОдна база данных.\nОбщие транзакции.'],['API: единицы измерения, лейблы и календарные сводки.','REST / JSON / OpenAPI.\nРасчёты на сервере.']];
for(const [a,b] of pairs)replace(1,a,b);

let s=wipe(2,'Архитектура приложения','Клиент показывает данные. Сервер проверяет правила и считает КБЖУ.');
const a=box(s,'Android\nKotlin / Compose',82,252,290,116);
const b=box(s,'REST API\nKotlin / Spring Boot',496,252,300,116);
const c=box(s,'PostgreSQL\nДанные и SQL',920,252,278,116);
connect(s,a,b);connect(s,b,c);
text(s,'HTTPS / JSON',380,210,155,32,18,false,C.muted);
text(s,'Spring JDBC',806,210,140,32,18,false,C.muted);
text(s,'Формы и состояние\nЗапросы и результат',82,402,290,80,23,false,C.muted);
text(s,'Сессии и права\nСценарии и транзакции',496,402,330,80,23,false,C.muted);
text(s,'Нормализованная БД\nРекурсивный расчёт',920,402,290,80,23,false,C.muted);
text(s,'Карточка, дневник и сводка используют один серверный механизм расчёта.',81,568,1120,68,24,true);

replace(3,'Модель хранения состава','Модули серверной части');
replace(3,'Сервер принимает граммы или проценты и сохраняет доли и коэффициенты.','Один процесс Spring Boot, предметные области разделены пакетами.');
replace(3,'Карточка продукта','Аккаунт и каталог');replace(3,'Связь состава','Питание и инфраструктура');
const ts=p.slides.items[3].tables.items;
const vals=[[
  ['Модуль','Ответственность'],['accounts','Аккаунты и сессии'],['profile','Личные ориентиры'],['catalog','Продукты и составы']
],[
  ['Модуль','Ответственность'],['diary','Записи питания'],['analytics','Календарные сводки'],['platform','Общие средства'],['Граница','Операции модулей']
]];
for(let j=0;j<ts.length;j++) for(let r=0;r<vals[j].length;r++) for(let col=0;col<2;col++) ts[j].getCell(r,col).value=vals[j][r][col];
replace(3,'1 : N','API');
const middle=(await p.inspect({select:'$.slides[3].elements[*]',include:['id','kind','text','position']})).records;
p.delete(middle.filter(e=>e.text==='API' || (e.kind==='shape' && e.position?.top>300 && e.position?.height<5)).map(e=>e.id));
ts[1].deleteRows({index:4,count:1});
replace(3,'Сумма долей ровно 100%. КБЖУ считаем при запросе по актуальным карточкам.','API, сценарии, домен и SQL разделены внутри каждого модуля.');
replace(3,'Массу рецепта не сохраняем. Съеденные граммы храним в дневнике.','Сценарий управляет транзакцией. Чужие таблицы модуль не изменяет.');

s=wipe(4,'Модель данных','Продукт, связь состава и факт питания хранятся отдельно.');
const user=box(s,'users\nАккаунт',82,218,240,94);
const prod=box(s,'products\nКарточка продукта',445,218,340,94);
const comp=box(s,'product_components\nРодитель, ребёнок\nДоля и выход',445,430,340,122);
const diary=box(s,'diary_entries\nПорция, время UTC',908,218,290,94);
connect(s,user,prod);connect(s,prod,diary);connect(s,prod,comp,'bottom','top');
text(s,'1 : N',350,238,80,30,20,true);
text(s,'1 : N',814,238,80,30,20,true);
text(s,'Две ссылки\nна products',807,452,170,72,21,false,C.muted);
text(s,'Владелец записи\nсвязан с users',908,338,290,72,21,false,C.muted);
text(s,'Лейблы и единицы\nв связанных таблицах',82,441,300,78,21,false,C.muted);
text(s,'Без снимка КБЖУ. Мягкое удаление карточки сохраняет историю питания.',81,595,1120,55,23,true);

s=wipe(5,'Сохранение и расчёт','Проверки при записи и вычисления при чтении решают разные задачи.');
text(s,'Сохранение состава',81,215,500,40,25,true,C.accent);
text(s,'Чтение КБЖУ',690,215,500,40,25,true,C.accent);
const save=['Блокировка владельца','Права, доли и отсутствие цикла','Одна транзакция и commit'];
const calc=['WITH RECURSIVE до листьев','Каждый путь графа даёт вклад','Сумма с учётом выхода блюда'];
for(let i=0;i<3;i++){
  text(s,String(i+1),81,280+i*88,40,40,25,true,C.sage);
  text(s,save[i],130,280+i*88,470,65,24,i===2);
  text(s,String(i+1),690,280+i*88,40,40,25,true,C.sage);
  text(s,calc[i],738,280+i*88,460,65,24,i===2);
}
text(s,'Массы рецепта превращаются в доли. Съеденная масса хранится в дневнике.',81,588,1120,60,23,true);

s=wipe(6,'Безопасность и выпуск','HTTPS, серверные права и независимые workflows компонентов');
text(s,'Access JWT и refresh с ротацией. Argon2id. Защита Keystore в Android.',81,210,1120,65,24,true);
const git=box(s,'main\nGitHub Actions',82,327,250,105);
const apk=box(s,'Android\nПодписанный APK',500,288,300,92);
const image=box(s,'Сервер\nGHCR по digest',500,468,300,92);
const vps=box(s,'VPS / Compose\nNginx, API и БД',930,468,270,92);
connect(s,git,apk);connect(s,git,image);connect(s,image,vps);
text(s,'Проверки компонента',391,397,300,32,19,false,C.muted);
text(s,'Миграции и readiness',828,577,360,32,19,false,C.muted);
text(s,'Копии БД и проверка восстановления. Перенос VPS через конфигурацию.',81,638,1120,35,21,false,C.muted);

replace(7,'Приёмка и итоги второго этапа','Итоги третьего этапа');
replace(7,'Подготовили ТЗ в DOCX/PDF, план семестра и 17 проверок приёмки.','Требования второго этапа дополнены проектированием решения.');
replace(7,'Сценарии','Технологии');
replace(7,'Вход, каталог, состав, запись питания и дневной итог.','Kotlin, Compose, Spring Boot, JDBC, PostgreSQL и Liquibase.');
replace(7,'Расчёты и права','Архитектура');
replace(7,'Контрольные числа, запрет циклов, изоляция пользователей и сохранение истории.','Границы компонентов, модули сервера и модель данных.');
replace(7,'Выпуск и данные','Проверки');
replace(7,'Подписанный APK, сервер, миграции, восстановление резервной копии.','Расчёты, циклы, доступ, миграции и восстановление.');
replace(7,'Пользовательские сценарии проверяем в Android.\nДоменные правила подтверждаем через API.','Объём семестрового MVP сохранён.\nКаждое ключевое решение связано с проверкой.');

const speech=await fs.readFile(path.join(ROOT,'docs/semester-mvp/stage-3/defense-notes.md'),'utf8');
const sections=[...speech.matchAll(/^## Слайд \d+\. (.+)\n\n([\s\S]*?)(?=\n## |(?![\s\S]))/gm)];
if(sections.length!==8) throw Error('Expected eight speech sections');
for(let i=0;i<8;i++)p.slides.items[i].speakerNotes.text=sections[i][2].trim();
const finalizer=await import(pathToFileURL(path.join(skill,'container_tools/artifact_tool_utils.mjs')));
const candidate=path.join(workspace,'build/candidate.pptx');
await (await PresentationFile.exportPptx(p)).save(candidate);
await finalizer.finalizePresentation({workspaceDir:workspace,candidatePath:candidate,finalPath:path.join(out,'Calorica-stage-3.pptx'),pythonExecutable:python,
 integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),
 explicitTotalSlideCount:8,requiredNativeTableOwnerSlides:[4],layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-heading-fit','--require-native-table-slide','4'],
 fontPolicy:{basis:'reference',families:['Helvetica Neue'],referencePath:source,referenceSha256:crypto.createHash('sha256').update(await fs.readFile(source)).digest('hex')},verifyArtifactToolImport:true,
 receiptPath:path.join(workspace,'build',`${path.basename(out)}.validation.json`)});
for(let i=0;i<8;i++) await fs.writeFile(path.join(out,`slide-${i+1}.png`),new Uint8Array(await (await p.slides.items[i].export({format:'png',scale:1.5})).arrayBuffer()));

// Compact technical figures for the A4 specification, using the same native objects.
const assets=path.join(out,'assets');await fs.mkdir(assets,{recursive:true});
async function diagram(name,width,height,draw){
  const d=Presentation.create({slideSize:{width,height}}),sl=d.slides.add();sl.background.fill=C.bg;draw(sl);
  await fs.writeFile(path.join(assets,`${name}.png`),new Uint8Array(await (await sl.export({format:'png',scale:2})).arrayBuffer()));
}
await diagram('architecture',800,470,s=>{
 const a=box(s,'Android\nФормы, состояние, запросы',110,24,580,100,C.pale,28);
 const b=box(s,'Kotlin / Spring Boot\nПрава, сценарии и расчёты',110,187,580,100,C.pale,28);
 const c=box(s,'PostgreSQL\nДанные и рекурсивный SQL',110,350,580,100,C.pale,28);
 connect(s,a,b,'bottom','top');connect(s,b,c,'bottom','top');
 text(s,'HTTPS',455,138,200,35,24,false,C.muted);text(s,'JDBC',455,301,200,35,24,false,C.muted);
});
await diagram('data-model',800,460,s=>{
 const a=box(s,'users\nАккаунт',25,25,275,95,C.pale,27);
 const b=box(s,'products\nКарточка',462,25,310,95,C.pale,27);
 const c=box(s,'diary_entries\nПорция и время',25,275,310,110,C.pale,27);
 const e=box(s,'product_components\nРодитель и ребёнок\nДоля и выход',462,275,310,140,C.pale,25);
 connect(s,a,b);connect(s,a,c,'bottom','top');connect(s,b,e,'bottom','top');connect(s,c,b,'right','bottom');
 text(s,'1 : N',340,48,100,35,25,true);text(s,'1 : N',80,183,100,35,25,true);text(s,'1 : N',657,183,110,35,25,true);
});
await diagram('deployment',800,440,s=>{
 const a=box(s,'GitHub Actions\nПроверки изменённой части',110,15,580,95,C.pale,28);
 const b=box(s,'Android\nПодписанный APK',25,185,325,95,C.pale,27);
 const c=box(s,'Сервер\nGHCR по digest',450,185,325,95,C.pale,27);
 const d=box(s,'VPS / Compose\nNginx, API, PostgreSQL',450,345,325,85,C.pale,25);
 connect(s,a,b,'bottom','top');connect(s,a,c,'bottom','top');connect(s,c,d,'bottom','top');
});
await diagram('process',800,400,s=>{
 text(s,'До автоматизации',25,12,740,40,29,true);
 const old=['Записать\nеду','Найти\nКБЖУ','Расчёт\nпорции','Сложить\nитог'];
 const now=['Выбрать\nпродукт','Указать\nграммы','Сохранить\nчерез API','Получить\nитог'];
 const first=[],second=[];
 for(let i=0;i<4;i++){first.push(box(s,old[i],25+i*198,70,170,90,C.pale,24));second.push(box(s,now[i],25+i*198,265,170,90,C.pale,24));}
 for(let i=0;i<3;i++){connect(s,first[i],first[i+1]);connect(s,second[i],second[i+1]);}
 text(s,'С Calorica',25,205,740,40,29,true);
});
console.log('Presentation and four figures ready:',out);
