import SwiftUI
import UniformTypeIdentifiers

private let accent=Color(red:0.23,green:0.34,blue:0.85)
private let appBackground=Color(red:0.97,green:0.975,blue:0.99)

struct DesktopView: View {
    @ObservedObject var model:AppModel
    var body:some View {
        VStack(spacing:0) {
            topBar
            Divider()
            if !model.ready {
                VStack(spacing:18) {
                    if model.busy { ProgressView() }
                    Image(systemName:"doc.text.magnifyingglass").font(.system(size:38)).foregroundStyle(accent)
                    Text(model.status).font(.headline)
                    if let error=model.error { Text(error).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center) }
                    if !model.busy { Button("다시 시작") { Task { await model.boot() } }.buttonStyle(.borderedProminent) }
                }.padding(35).frame(maxWidth:.infinity,maxHeight:.infinity)
            } else {
                ScrollView {
                    VStack(alignment:.leading,spacing:18) {
                        if model.tab=="tasks" { TasksView(model:model) }
                        else if model.tab=="history" { LocalHistoryView(model:model) }
                        else {
                            if model.report==nil { inputView }
                            else { if model.report?.sections?.isEmpty==false { StructuredReportView(model:model) } else { ReportView(model:model) } }
                        }
                        if let error=model.error {
                            Label(error,systemImage:"exclamationmark.circle.fill").font(.callout).foregroundStyle(Color.red).padding(13).frame(maxWidth:.infinity,alignment:.leading).background(Color.red.opacity(0.06),in:RoundedRectangle(cornerRadius:10)).textSelection(.enabled)
                        }
                        if let notice=model.notice { Label(notice,systemImage:"checkmark.circle").font(.callout).foregroundStyle(.green) }
                    }.padding(22)
                }
            }
            Divider()
            HStack(spacing:6) {
                Circle().fill(model.ready ? Color.green:Color.orange).frame(width:5,height:5)
                Text(model.busy ? model.status:"기록은 이 Mac에 저장됩니다").lineLimit(1)
                Spacer()
                if model.shortcutAvailable { Text("⌃⌥Space 열기") }
            }.font(.system(size:10)).foregroundStyle(.secondary).padding(.horizontal,20).padding(.vertical,11)
        }
        .background(appBackground).tint(accent).frame(minWidth:460,minHeight:600)
        .sheet(isPresented:$model.showSettings) { PersonalSettingsView(model:model) }
        .sheet(isPresented:$model.showSource) { SourceView(model:model) }
        .sheet(item:$model.selectedFinding) { f in TaskReviewView(model:model,finding:f) }
        .onDrop(of:[UTType.fileURL],isTargeted:$model.dropTarget) { providers in
            guard !model.busy,let provider=providers.first else { return false }
            _=provider.loadObject(ofClass:URL.self) { url,_ in if let url { Task { @MainActor in model.acceptFile(url) } } }
            return true
        }
        .overlay { if model.dropTarget { RoundedRectangle(cornerRadius:12).stroke(accent,lineWidth:3).padding(3).allowsHitTesting(false) } }
    }
    private var topBar:some View {
        HStack(spacing:10) {
            Image(systemName:"doc.text.magnifyingglass").font(.system(size:20,weight:.medium)).foregroundStyle(accent)
            Text("ActionDoc").font(.system(size:17,weight:.bold))
            Text("AI").font(.system(size:10,weight:.bold)).foregroundStyle(accent)
            Spacer()
            Button { model.tab="analyze" } label:{ Image(systemName:"plus.square").frame(width:24,height:22) }.help("문서 분석")
            Button { model.tab="tasks";Task { await model.refreshTasks() } } label:{ HStack(spacing:4){Image(systemName:"checklist");if model.taskCount>0{Text("\(model.taskCount)").font(.caption)}} }.help("내 작업")
            Button{model.showSettings=true}label:{Image(systemName:"gearshape").frame(width:24,height:22)}.help("AI 설정").disabled(model.busy)
            Button{model.tab="history"}label:{Image(systemName:"clock.arrow.circlepath").frame(width:24,height:22)}.help("이 Mac의 분석 기록")
        }.buttonStyle(.plain).padding(.horizontal,21).padding(.vertical,17).background(.white)
    }
    private var inputView:some View {
        VStack(alignment:.leading,spacing:17) {
            VStack(alignment:.leading,spacing:6) {
                Text("복잡한 안내문,\n내가 할 일만 명확하게.").font(.system(size:25,weight:.bold)).tracking(-0.8)
                Text("문서의 조건과 필요한 조치를 원문과 함께 확인하세요.").font(.system(size:12)).foregroundStyle(.secondary)
            }.padding(.bottom,4)
            if let file=model.file {
                HStack(spacing:12) {
                    Image(systemName:"doc.fill").font(.system(size:26)).foregroundStyle(accent)
                    VStack(alignment:.leading,spacing:3){Text(file.lastPathComponent).font(.callout).fontWeight(.medium).lineLimit(2);Text("분석할 준비가 되었어요").font(.caption).foregroundStyle(.secondary)}
                    Spacer();Button {model.file=nil;model.resetReport()} label:{Image(systemName:"xmark.circle.fill").foregroundStyle(.secondary)}.buttonStyle(.plain).disabled(model.busy)
                }.padding(18).frame(maxWidth:.infinity,alignment:.leading).background(.white,in:RoundedRectangle(cornerRadius:12))
            } else if model.text.isEmpty {
                Button { model.pickFile() } label:{
                    VStack(spacing:9) {
                        Image(systemName:"square.and.arrow.down").font(.system(size:27,weight:.light)).foregroundStyle(accent).padding(.bottom,5)
                        Text("파일을 여기에 놓아주세요").font(.system(size:14,weight:.semibold)).foregroundStyle(.primary)
                        Text("클릭해서 파일 선택 · 최대 \(UploadLimits.maxFileMB)MB").font(.caption).foregroundStyle(.secondary)
                        Text("PDF(최대 50쪽) · HWP · HTML · TXT · MD").font(.system(size:10)).foregroundStyle(.tertiary).padding(.top,4)
                    }.frame(maxWidth:.infinity).padding(.vertical,28).background(.white,in:RoundedRectangle(cornerRadius:12)).overlay(RoundedRectangle(cornerRadius:12).strokeBorder(accent.opacity(0.3),style:StrokeStyle(lineWidth:1,dash:[5,4])))
                }.buttonStyle(.plain).disabled(model.busy)
            } else {
                VStack(alignment:.leading,spacing:8) {
                    HStack { Text("붙여넣은 안내문").font(.caption).foregroundStyle(.secondary);Spacer();Button("비우기"){model.text="";model.resetReport()}.font(.caption) }
                    TextEditor(text:$model.text).font(.system(size:12)).frame(height:155).scrollContentBackground(.hidden).padding(7).background(.white,in:RoundedRectangle(cornerRadius:8)).overlay(RoundedRectangle(cornerRadius:8).stroke(Color.gray.opacity(0.18))).disabled(model.busy)
                }
            }
            HStack {
                Button {model.paste()} label:{Label("복사한 내용 붙여넣기",systemImage:"doc.on.clipboard")}.buttonStyle(.bordered).controlSize(.regular)
                Spacer()
                Button("샘플 보기"){model.sample()}.font(.caption).buttonStyle(.plain).foregroundStyle(accent)
            }.disabled(model.busy)
            VStack(alignment:.leading,spacing:8) {
                Picker("요약 목적",selection:$model.summaryPurpose){ForEach(SummaryPurpose.allCases){purpose in Text(purpose.label).tag(purpose)}}.disabled(model.busy)
                Text(model.summaryPurpose.hint).font(.caption).foregroundStyle(.secondary)
                Text("PDF 최대 \(UploadLimits.maxPdfPages)쪽 · 파일 최대 \(UploadLimits.maxFileMB)MB").font(.caption).foregroundStyle(.secondary)
            }
            VStack(alignment:.leading,spacing:8) {
                HStack(spacing:5) {
                    Text("추가로 알려줄 내용").fontWeight(.semibold)
                    Text("(선택)").foregroundStyle(.secondary)
                }.font(.caption)
                TextField("예: 서울 거주, 28세 직장인, 세전 연봉 5,000만원. 무주택이고 미혼이에요.",text:$model.context,axis:.vertical)
                    .lineLimit(2...4).textFieldStyle(.roundedBorder).font(.system(size:12))
                    .accessibilityLabel("추가로 알려줄 내용 (선택)").disabled(model.busy)
                Text(model.useAI ? "입력한 상황을 문서 조건과 비교하고 확인할 사항을 정리합니다.":"추가 정보는 기록에 함께 저장됩니다. 무료 기본 정리에서는 개인별 적용 여부를 판단하지 않습니다.").font(.system(size:10)).foregroundStyle(.secondary)
                Text("분석 결과·입력한 상황·확인한 작업은 이 Mac에만 저장됩니다. 서버에 기록을 보관하지 않습니다.").font(.system(size:10)).foregroundStyle(.tertiary)
            }
            VStack(alignment:.leading,spacing:9){
                Picker("요약 방식",selection:$model.useAI){Text("무료 기본 정리").tag(false);Text("내 API 키로 상세 요약").tag(true)}.pickerStyle(.segmented).disabled(model.busy)
                if model.useAI {
                    Text("AI가 문맥을 요약하고 조건·예외와 원문 근거를 검증합니다. 본인의 OpenAI API 사용량에 따라 비용이 발생합니다.").font(.caption).foregroundStyle(.secondary)
                    Button(model.aiConfigured ? "AI 연결 설정":"OpenAI API 키 등록"){model.showSettings=true}.font(.caption).disabled(model.busy)
                    Toggle("문서와 추가 정보의 OpenAI 전송 및 내 API 사용 비용에 동의합니다.",isOn:$model.consent).toggleStyle(.checkbox).font(.caption).disabled(model.busy)
                }else{
                    Text("가입·API 키·AI 사용료 없이 주요 원문을 발췌합니다. 문맥을 새로 쓰는 AI 요약은 아닙니다. 문서는 이 Mac에서 처리합니다.").font(.caption).foregroundStyle(.secondary)
                }
            }.onChange(of:model.useAI){_,_ in model.consent=false}

            Button{Task{await model.analyze()}}label:{
                HStack(spacing:8){if model.busy{ProgressView().controlSize(.small)}else{Image(systemName:"sparkles")};Text(model.busy ? "분석하고 있어요…":"문서 요약하기").fontWeight(.semibold);Spacer();Image(systemName:"arrow.right")}.padding(.vertical,7).frame(maxWidth:.infinity)
            }.buttonStyle(.borderedProminent).controlSize(.large).disabled(!model.canAnalyze || (model.useAI && (!model.aiConfigured || !model.consent))).keyboardShortcut(.return,modifiers:.command)
            if model.busy { Text(model.status).font(.caption).foregroundStyle(.secondary) }
            Text("스캔 PDF도 자동으로 OCR 처리해요. 작업은 확인한 뒤 등록합니다.").font(.system(size:10)).foregroundStyle(.tertiary)
        }
    }
}

struct ReportView:View {
    @ObservedObject var model:AppModel
    var body:some View {
        VStack(alignment:.leading,spacing:17) {
            HStack(alignment:.top) {
                VStack(alignment:.leading,spacing:5) {
                    Text("다음 내용을 확인하세요.").font(.system(size:23,weight:.bold)).tracking(-0.7)
                    Text(model.document?.name ?? "안내문").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Button("새 문서"){model.resetReport();model.text="";model.file=nil;model.context=""}.font(.caption)
            }
            if let report=model.report {
                HStack(spacing:6) { Image(systemName:report.mode=="ai" ? "sparkles":"text.magnifyingglass");Text(report.mode=="ai" ? "AI 분석 · 원문 인용 검증":"규칙 기반 미리보기 · AI 분석 아님");Spacer();Text(SourceLocation.count(model.document?.pages ?? 1)) }.font(.system(size:10)).foregroundStyle(.secondary)
                ActionRoutesView(model:model,report:report)
                ScrollView(.horizontal,showsIndicators:false) {
                    HStack(spacing:6) { ForEach(["핵심","요금","날짜","할 일","변경","영향","전체"],id:\.self){ label in Button(label){model.reportFilter=label}.buttonStyle(.bordered).tint(model.reportFilter==label ? accent:.gray).controlSize(.small) } }
                }
                if visibleFindings(report).isEmpty { Text("확인된 항목이 없어요. 원문과 확인 질문을 살펴보세요.").font(.callout).foregroundStyle(.secondary).padding(.vertical,20) }
                ForEach(visibleFindings(report)) { finding in FindingCard(model:model,finding:finding) }
                if model.reportFilter=="핵심",report.findings.count>visibleFindings(report).count { Button("전체 분석 \(report.findings.count)개 보기"){model.reportFilter="전체"}.font(.caption).frame(maxWidth:.infinity) }
                ForEach(report.warnings.filter{$0.contains("자동 OCR 적용") || $0.contains("OCR 인식 신뢰도") || $0.contains("빈 페이지") || $0.contains("HWP 본문")},id:\.self) { warning in
                    Label(warning,systemImage:"text.viewfinder").font(.caption).foregroundStyle(.orange).fixedSize(horizontal:false,vertical:true)
                }
                if !report.questions.isEmpty {
                    VStack(alignment:.leading,spacing:9){Label("확인 필요",systemImage:"questionmark.circle").font(.callout).fontWeight(.semibold);ForEach(report.questions,id:\.self){Text("• "+$0).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal:false,vertical:true)}}.padding(15).frame(maxWidth:.infinity,alignment:.leading).background(Color.orange.opacity(0.06),in:RoundedRectangle(cornerRadius:10))
                }
                DisclosureGroup("분석 방식과 주의사항") { VStack(alignment:.leading,spacing:8){ForEach(report.warnings,id:\.self){Text($0).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal:false,vertical:true)}}.padding(.top,9) }.font(.caption).foregroundStyle(.secondary)
                HStack { Button {model.showSource=true} label:{Label("원문 전체",systemImage:"doc.text.magnifyingglass")};Spacer();Button {model.saveMarkdown()} label:{Label("마크다운 저장",systemImage:"square.and.arrow.down")} }.font(.caption)
                DisclosureGroup("추가 정보로 다시 분석") {
                    VStack(alignment:.leading,spacing:10){TextField("거주지·나이·소득·가구·주택 등 비교할 상황을 적어 주세요",text:$model.context,axis:.vertical).lineLimit(2...4).textFieldStyle(.roundedBorder);Button("다시 분석하기"){Task{await model.analyze()}}.disabled(!model.canAnalyze || (model.useAI && (!model.aiConfigured || !model.consent)))}.padding(.top,8)
                }.font(.caption).foregroundStyle(.secondary)
            }
        }
    }
    private func visibleFindings(_ report:AnalysisReport) -> [Finding] {
        let category=["요금":"fee","날짜":"date","할 일":"action","변경":"change","영향":"impact"][model.reportFilter]
        if model.reportFilter=="전체" { return report.findings }
        if let category { return report.findings.filter{$0.category==category} }
        // Prefer clauses containing amounts over headings when displaying the compact overview.
        let fees=report.findings.filter{$0.category=="fee" && $0.quote.range(of:"[0-9]",options:.regularExpression) != nil}
        var chosen=Array(fees.prefix(1))
        for category in ["date","action","impact"] { chosen += report.findings.filter{$0.category==category}.prefix(category=="action" ? 2:1) }
        if chosen.isEmpty { return Array(report.findings.prefix(4)) }
        return chosen
    }
}

struct FindingCard:View {
    @ObservedObject var model:AppModel
    let finding:Finding
    var body:some View {
        VStack(alignment:.leading,spacing:10) {
            HStack { Label(finding.label,systemImage:finding.symbol).font(.caption).fontWeight(.semibold).foregroundStyle(accent);Spacer();Text(SourceLocation.page(finding.page)).font(.system(size:10)).foregroundStyle(.secondary) }
            Text(finding.detail).font(.system(size:13)).lineSpacing(4).fixedSize(horizontal:false,vertical:true).textSelection(.enabled)
            if let date=finding.date { Text(finding.category=="action" ? "처리 기한 · \(date)":date).font(.caption).foregroundStyle(.secondary) }
            DisclosureGroup(model.document?.ocrPages?.contains(finding.page)==true ? "OCR 인식문 · 원본 확인":"원문 근거") { Text(finding.quote).font(.system(size:11)).foregroundStyle(.secondary).textSelection(.enabled).padding(.top,5).frame(maxWidth:.infinity,alignment:.leading) }.font(.system(size:10)).foregroundStyle(.secondary)
            if finding.category=="action" {
                Button(model.registered(finding) ? "등록된 작업":"확인 후 작업 등록") { model.selectedFinding=finding }.font(.caption).buttonStyle(.bordered).disabled(model.registered(finding) || model.busy)
            }
        }.padding(16).frame(maxWidth:.infinity,alignment:.leading).background(Color.white,in:RoundedRectangle(cornerRadius:12)).overlay(RoundedRectangle(cornerRadius:12).stroke(Color.gray.opacity(0.1)))
    }
}

struct TasksView:View {
    @ObservedObject var model:AppModel
    var body:some View {
        VStack(alignment:.leading,spacing:16) {
            HStack { VStack(alignment:.leading,spacing:5){Text("확인한 일을 하나씩.").font(.system(size:23,weight:.bold));Text("남은 작업 \(model.taskCount)개").font(.caption).foregroundStyle(.secondary)};Spacer();Button{Task{await model.refreshTasks()}}label:{Image(systemName:"arrow.clockwise")}.buttonStyle(.plain).disabled(model.busy) }
            Toggle("완료한 작업도 보기",isOn:$model.showDone).toggleStyle(.checkbox).font(.caption).foregroundStyle(.secondary)
            if filtered.isEmpty { VStack(spacing:10){Image(systemName:"checkmark.circle").font(.system(size:32)).foregroundStyle(.secondary);Text("표시할 작업이 없어요").font(.headline);Text("분석 결과에서 필요한 일을 등록해 보세요.").font(.caption).foregroundStyle(.secondary)}.frame(maxWidth:.infinity).padding(.vertical,55) }
            ForEach(filtered) { task in
                HStack(alignment:.top,spacing:12) {
                    Button{Task{await model.complete(task)}}label:{Image(systemName:task.status=="DONE" ? "checkmark.circle.fill":"circle").font(.system(size:21)).foregroundStyle(task.status=="DONE" ? .green:accent)}.buttonStyle(.plain).help(task.status=="DONE" ? "할 일로 되돌리기":"완료 처리").disabled(model.busy)
                    VStack(alignment:.leading,spacing:8) {
                        Text(task.title).font(.system(size:13,weight:.semibold)).strikethrough(task.status=="DONE")
                        Text(task.description).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal:false,vertical:true)
                        Text(task.dueDate.map{"기한 · \($0)"} ?? "기한 미지정").font(.system(size:10)).foregroundStyle(.secondary)
                        DisclosureGroup("등록 시 원문") { Text(task.quote).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }.font(.system(size:10)).foregroundStyle(.secondary)
                    };Spacer(minLength:0)
                }.padding(16).frame(maxWidth:.infinity,alignment:.leading).background(.white,in:RoundedRectangle(cornerRadius:12))
            }
        }
    }
    private var filtered:[WorkItem]{model.tasks.filter{model.showDone || $0.status != "DONE"}}
}

struct StructuredReportView:View {
    @ObservedObject var model:AppModel
    var body:some View {
        VStack(alignment:.leading,spacing:17) {
            if let report=model.report {
                HStack(alignment:.top){
                    VStack(alignment:.leading,spacing:6){
                        Text(report.mode=="ai" ? "문서 핵심 요약":report.documentType=="PUBLIC_NOTICE" ? "모집 공고 항목별 정리":"문서 항목별 정리").font(.system(size:10,weight:.semibold)).foregroundStyle(accent)
                        Text(report.title).font(.system(size:20,weight:.bold)).tracking(-0.5).fixedSize(horizontal:false,vertical:true)
                    }
                    Spacer(minLength:5);Button("새 문서"){model.resetReport();model.text="";model.file=nil;model.context=""}.font(.caption)
                }
                HStack(spacing:6){Image(systemName:report.mode=="local" ? "doc.text":"sparkles");Text(report.mode=="ai" ? "문서 분석 · 근거 확인":"원문 항목 정리 · AI 요약 아님");Spacer();Text(SourceLocation.count(model.document?.pages ?? 1))}.font(.system(size:10)).foregroundStyle(.secondary)


                if report.periodStatus=="CLOSED" {
                    Label("공고에 적힌 접수 기간은 이미 종료됐습니다.",systemImage:"calendar.badge.exclamationmark").font(.system(size:12,weight:.semibold)).foregroundStyle(.orange).padding(13).frame(maxWidth:.infinity,alignment:.leading).background(Color.orange.opacity(0.07),in:RoundedRectangle(cornerRadius:10))
                }
                if model.document?.type=="HWP" { Text("표의 배치·이미지 속 글자·원본 쪽 번호는 보존되지 않습니다. 원문과 함께 확인해 주세요.").font(.system(size:11)).foregroundStyle(.orange) }
                if let overview=report.overview { Text(overview).font(.system(size:12)).foregroundStyle(.secondary).lineSpacing(4).fixedSize(horizontal:false,vertical:true) }
                ActionRoutesView(model:model,report:report)
                if let checks=report.personalChecks,!checks.isEmpty {
                    VStack(alignment:.leading,spacing:12){
                        Text("내 상황과 비교").font(.system(size:16,weight:.bold))
                        Text("각 조건의 비교 결과입니다. 기관의 최종 자격·대출 심사 결과를 뜻하지 않습니다.").font(.caption).foregroundStyle(.secondary)
                        ForEach(checks){check in PersonalCheckCard(check:check)}
                    }
                }
                ForEach(report.sections ?? []) { section in
                    VStack(alignment:.leading,spacing:11) {
                        HStack{Text(section.title).font(.system(size:13,weight:.semibold)).foregroundStyle(accent);Spacer();Text(section.aiRefined ? (report.mode=="ai" ? "LLM 분석":"AI 문장 정리"):"원문 항목 정리").font(.system(size:9)).foregroundStyle(.tertiary)}
                        Text(section.body).font(.system(size:13)).lineSpacing(5).lineLimit(section.body.count>260 ? 5:nil).textSelection(.enabled)
                        if section.body.count>260 { DisclosureGroup(report.mode=="ai" ? "요약 전체 보기":"발췌 전체 보기"){Text(section.body).font(.system(size:12)).lineSpacing(5).fixedSize(horizontal:false,vertical:true).textSelection(.enabled).frame(maxWidth:.infinity,alignment:.leading).padding(.top,6)}.font(.system(size:11)) }
                        DisclosureGroup("근거 \(section.evidence.count)곳 · \(SourceLocation.pages(section.evidence.map(\.page)))") {
                            VStack(alignment:.leading,spacing:8){ForEach(Array(section.evidence.enumerated()),id:\.offset){_,evidence in VStack(alignment:.leading,spacing:3){Text("\(SourceLocation.page(evidence.page)) · \(evidence.blockId)").font(.system(size:9)).foregroundStyle(.tertiary);Text(evidence.quote).font(.system(size:11)).foregroundStyle(.secondary).textSelection(.enabled)}}}.padding(.top,5)
                        }.font(.system(size:10)).foregroundStyle(.secondary)
                    }.padding(16).frame(maxWidth:.infinity,alignment:.leading).background(.white,in:RoundedRectangle(cornerRadius:12)).overlay(RoundedRectangle(cornerRadius:12).stroke(Color.gray.opacity(0.1)))
                }
                if report.mode=="ai" || report.periodStatus != "CLOSED" {
                    ForEach(report.findings.filter{$0.category=="action"}){finding in FindingCard(model:model,finding:finding)}
                }
                if !report.questions.isEmpty { VStack(alignment:.leading,spacing:8){Text("내 조건에서 확인할 점").font(.caption).fontWeight(.semibold);ForEach(report.questions,id:\.self){Text($0).font(.caption).foregroundStyle(.secondary)}}.padding(14).frame(maxWidth:.infinity,alignment:.leading).background(Color.orange.opacity(0.04),in:RoundedRectangle(cornerRadius:10)) }
                if let facts=report.facts,!facts.isEmpty {
                    DisclosureGroup("전체 추출 조건 \(facts.count)개 보기") {
                        VStack(alignment:.leading,spacing:12){ForEach(facts){fact in
                            VStack(alignment:.leading,spacing:5){Text(fact.topic).font(.caption).fontWeight(.semibold);Text(fact.statement).font(.caption).textSelection(.enabled);EvidenceDisclosure(evidence:fact.evidence)}
                        }}.padding(.top,8)
                    }.font(.caption)
                }
                if let stages=report.stages { ForEach(Array(stages.enumerated()),id:\.offset){_,stage in HStack(alignment:.top){Image(systemName:"checkmark.circle").foregroundStyle(.green);VStack(alignment:.leading){Text(stage.name).fontWeight(.medium);Text(stage.detail).foregroundStyle(.secondary)}}.font(.system(size:10))} }
                DisclosureGroup("분석 방식과 확인 사항"){VStack(alignment:.leading,spacing:7){ForEach(report.warnings,id:\.self){Text($0).font(.caption).foregroundStyle(.secondary)}}.padding(.top,8)}.font(.caption).foregroundStyle(.secondary)
                HStack{Button{model.showSource=true}label:{Label("원문 전체",systemImage:"doc.text.magnifyingglass")};Spacer();Button{model.saveMarkdown()}label:{Label("마크다운 저장",systemImage:"square.and.arrow.down")}}.font(.caption)
                DisclosureGroup("추가로 알려줄 내용 (선택)"){
                    VStack(alignment:.leading,spacing:10){TextField("거주지·나이·소득·가구·주택 등 비교할 상황을 적어 주세요",text:$model.context,axis:.vertical).lineLimit(2...4).textFieldStyle(.roundedBorder);Button("다시 분석하기"){Task{await model.analyze()}}.disabled(!model.canAnalyze || (model.useAI && (!model.aiConfigured || !model.consent)))}.padding(.top,8)
                }.font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

struct EvidenceDisclosure:View {
    let evidence:[Evidence]
    var body:some View {
        DisclosureGroup("원문 근거 · \(SourceLocation.pages(evidence.map(\.page)))"){
            VStack(alignment:.leading,spacing:8){ForEach(Array(evidence.enumerated()),id:\.offset){_,e in VStack(alignment:.leading,spacing:3){Text("\(SourceLocation.page(e.page)) · \(e.blockId)").font(.system(size:9)).foregroundStyle(.tertiary);Text(e.quote).font(.system(size:11)).foregroundStyle(.secondary).textSelection(.enabled)}}}.padding(.top,5)
        }.font(.system(size:10)).foregroundStyle(.secondary)
    }
}
struct PersonalCheckCard:View {
    let check:PersonalCheck
    private var label:String { ["MATCH":"조건 일치","MISMATCH":"조건 불일치","UNKNOWN":"추가 확인","IRRELEVANT":"관련 조건 없음"][check.status] ?? "추가 확인" }
    private var color:Color { check.status=="MATCH" ? .green:check.status=="MISMATCH" ? .red:check.status=="IRRELEVANT" ? .secondary:.orange }
    var body:some View {
        VStack(alignment:.leading,spacing:9){
            HStack{Text(check.criterion).font(.system(size:13,weight:.semibold));Spacer();Text(label).font(.system(size:10,weight:.semibold)).foregroundStyle(color)}
            Text(check.reason).font(.system(size:12)).lineSpacing(4).fixedSize(horizontal:false,vertical:true).textSelection(.enabled)
            if !check.profileQuote.isEmpty { Text("내가 입력한 정보: “\(check.profileQuote)”").font(.system(size:10)).foregroundStyle(.secondary).textSelection(.enabled) }
            EvidenceDisclosure(evidence:check.evidence)
        }.padding(14).frame(maxWidth:.infinity,alignment:.leading).background(Color.white,in:RoundedRectangle(cornerRadius:10)).overlay(RoundedRectangle(cornerRadius:10).stroke(color.opacity(0.2)))
    }
}

struct LocalHistoryView:View {
    @ObservedObject var model:AppModel
    var body:some View {
        VStack(alignment:.leading,spacing:16){
            Text("이 Mac의 분석 기록").font(.system(size:23,weight:.bold))
            Text("가입 없이 이 Mac에 저장합니다. 서버 연결이 없어도 결과와 작업을 확인할 수 있어요.").font(.caption).foregroundStyle(.secondary)
            HStack{Button("기록 내보내기"){model.exportRecords()};Button("기록 가져오기"){model.importRecords()}}.font(.caption).disabled(model.busy)
            if model.history.isEmpty{Text("저장한 분석이 아직 없어요. 분석을 마치면 이곳에 자동으로 저장됩니다.").font(.callout).foregroundStyle(.secondary).padding(.vertical,30)}
            ForEach(model.history){item in
                VStack(alignment:.leading,spacing:9){
                    HStack{Text(item.report.title).font(.system(size:14,weight:.semibold)).lineLimit(2);Spacer();Button("결과 열기"){model.openHistory(item)}.font(.caption)}
                    Text(item.document.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Text(String(item.savedAt.prefix(10))).font(.system(size:10)).foregroundStyle(.secondary)
                    if let overview=item.report.overview{Text(overview).font(.caption).lineLimit(3).foregroundStyle(.secondary)}
                }.padding(16).frame(maxWidth:.infinity,alignment:.leading).background(.white,in:RoundedRectangle(cornerRadius:12))
            }
        }
    }
}
