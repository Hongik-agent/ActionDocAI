import test from 'node:test';
import assert from 'node:assert/strict';
import {sectionText,aiText,reportText,copyControl} from '../src/copy.js';
const block={text:'지원금 100만원. 다만 중복 수혜자는 제외합니다.',page:2,id:'b1'};
const section={title:'지원 내용',excerpts:[block],evidence:[block]};
test('copy text includes only its section, retains exceptions and source positions',()=>{
  assert.equal(sectionText(section),'지원 내용\n• '+block.text+' (2쪽 · b1)');
  assert.match(sectionText({...section,excerpts:[]}),/중복 수혜자는 제외/);
  assert.match(sectionText({...section,excerpts:[],evidence:[]}),/조건이 없다는 뜻은 아니므로/);
});
test('whole report copy includes current verified AI items and no markup or duplicate citations',()=>{
  const items=[{section:'지원 요건',statement:'중복 수혜자는 제외됩니다.',blocks:[block]}];
  const report={name:'안내문',purpose:'SUPPORT',warnings:['OCR 원본 확인'],sections:[section]};
  const text=reportText(report,items);
  assert.match(text,/OCR 원본 확인/);assert.match(text,/AI 상세 요약/);assert.match(text,/원문 위치: 2쪽 · b1/);
  assert.match(text,/기본 정리/);assert.doesNotMatch(reportText(report),/AI 상세 요약/);
  assert.equal(aiText([...items,...items]).split('지원 요건').length,2);
});
test('copy reports success only after clipboard resolves and exposes permission failures',async t=>{
  const oldDocument=globalThis.document,oldNavigator=Object.getOwnPropertyDescriptor(globalThis,'navigator');
  const el=()=>({children:[],setAttribute(){},append(...nodes){this.children.push(...nodes);}});
  globalThis.document={createElement:el};let copied;
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{clipboard:{writeText:async text=>{copied=text;}}}});
  t.after(()=>{if(oldDocument===undefined)delete globalThis.document;else globalThis.document=oldDocument;if(oldNavigator)Object.defineProperty(globalThis,'navigator',oldNavigator);else delete globalThis.navigator;});
  const wrapper=copyControl(()=>sectionText(section));const [button,status]=wrapper.children;
  await button.onclick();assert.equal(copied,sectionText(section));assert.equal(status.textContent,'복사 완료');assert.equal(button.disabled,false);
  navigator.clipboard.writeText=async()=>{throw Error('denied');};
  await button.onclick();assert.match(status.textContent,/권한/);assert.equal(button.disabled,false);
});
