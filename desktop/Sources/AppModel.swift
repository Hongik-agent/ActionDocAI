import AppKit
import SwiftUI
import UniformTypeIdentifiers
@MainActor final class AppModel:ObservableObject {
    @Published var showSettings=false
    @Published var apiKeyInput=""
    @Published var modelName="gpt-4.1-mini"
    @Published var aiConfigured=false
    @Published var useAI=false
    let server=PersonalServer()
    @Published var dropTarget=false
    @Published var reportFilter="핵심"
    @Published var showDone=false
    @Published var reviewTitle=""
    @Published var reviewDescription=""
    @Published var reviewDue=""
    @Published var reviewApproved=false
    @Published var ready=false
    @Published var serviceReady=false
    @Published var busy=false
    @Published var status="Mac의 기록을 준비하고 있어요…"
    @Published var serviceMessage:String?
    @Published var error:String?
    @Published var text=""
    @Published var file:URL?
    @Published var context=""
    @Published var summaryPurpose:SummaryPurpose = .general
    @Published var consent=false
    @Published var report:AnalysisReport?
    @Published var document:SourceDocument?
    @Published var tasks:[WorkItem]=[]
    @Published var history:[HistoryEntry]=[]
    @Published var tab="analyze"
    @Published var showSource=false
    @Published var selectedFinding:Finding?
    @Published var notice:String?
    @Published var analysesRemaining:Int?
    @Published var shortcutAvailable=true
    private(set) var api:APIClient?
    private(set) var archive:LocalArchive?
    var taskCount:Int{tasks.filter{$0.status=="TODO"}.count}
    var canAnalyze:Bool{ready && serviceReady && !busy && (file != nil || !text.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty)}
    func boot() async {
        busy=true;error=nil;defer{busy=false}
        do{if archive==nil{archive=try LocalArchive()};refreshLocal();ready=true}catch{self.error=error.localizedDescription;status="Mac 기록을 읽지 못했습니다";return}
        serviceReady=false;api=nil
        do{
            let prefs=PersonalSettings.load();modelName=prefs.model
            let key=prefs.connected ? try PersonalKeychain.load():""
            let client=try await server.start(key:key,model:modelName);api=client
            aiConfigured=client.aiConfigured;serviceReady=true;serviceMessage=nil;status="내 컴퓨터에서 실행 중"
        }catch{self.error=error.localizedDescription;status="분석 엔진을 시작하지 못했습니다"}
    }
    func saveSettings() async {
        guard !busy else{return};busy=true;error=nil;defer{busy=false}
        do{
            let model=modelName.trimmingCharacters(in:.whitespacesAndNewlines)
            guard !model.isEmpty,model.count<=100 else{throw ServiceError(message:"모델 이름을 확인해 주세요.")}
            var prefs=PersonalSettings.load();prefs.model=model
            if !apiKeyInput.isEmpty {try PersonalKeychain.save(apiKeyInput.trimmingCharacters(in:.whitespacesAndNewlines));prefs.connected=true}
            try prefs.save();apiKeyInput="";showSettings=false;resetReport();await boot()
        }catch{self.error=error.localizedDescription}
    }
    func disconnectAI() async {
        guard !busy else{return}
        do{try PersonalKeychain.remove();useAI=false;consent=false;var p=PersonalSettings.load();p.connected=false;try p.save();apiKeyInput="";showSettings=false;resetReport();await boot()}catch{self.error=error.localizedDescription}
    }

    private func refreshLocal(){tasks=archive?.data.tasks ?? [];history=archive?.data.history ?? []}
    func refreshTasks() async{refreshLocal()}
    func pickFile(){guard !busy else{return};let p=NSOpenPanel();p.title="분석할 안내문 선택";p.prompt="선택";p.allowedContentTypes=[.pdf,.html,.plainText,UTType(filenameExtension:"md") ?? .plainText,UTType(filenameExtension:"hwp") ?? UTType(importedAs:"com.hancom.hwp")];p.allowsMultipleSelection=false;p.canChooseDirectories=false;if p.runModal() == .OK,let url=p.url{acceptFile(url)}}
    func acceptFile(_ url:URL){guard !busy else{return};guard ["pdf","hwp","html","htm","txt","md"].contains(url.pathExtension.lowercased())else{error="PDF, HWP, HTML, TXT, MD 파일을 선택해 주세요.";return};do{let v=try url.resourceValues(forKeys:[.fileSizeKey,.isRegularFileKey]);guard v.isRegularFile==true,(v.fileSize ?? 0)<=UploadLimits.maxFileBytes else{throw ServiceError(message:"\(UploadLimits.maxFileMB)MB 이하 문서를 선택해 주세요.")};file=url;text="";resetReport();tab="analyze"}catch{self.error=error.localizedDescription}}
    func paste(){guard !busy else{return};guard let content=NSPasteboard.general.string(forType:.string),!content.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else{error="복사한 텍스트가 없어요.";return};guard content.count<=160000 else{error="본문은 160,000자 이하여야 합니다.";return};file=nil;text=content;resetReport();tab="analyze"}
    func resetReport(){report=nil;document=nil;error=nil;notice=nil;reportFilter="핵심"}
    func sample(){guard !busy else{return};file=nil;text=sampleNotice;resetReport();tab="analyze"}
    func analyze() async {
        guard canAnalyze,let api else{return};if useAI && !aiConfigured{error="AI 설정에서 본인의 OpenAI API 키를 연결해 주세요.";return};if useAI && !consent{error="문서 전송과 본인 API 사용 비용에 동의해 주세요.";return};guard context.count<=4000 else{error="추가 정보는 4,000자 이하여야 합니다.";return}
        busy=true;error=nil;notice=nil;report=nil;document=nil;defer{busy=false}
        do{status="문서와 스캔 페이지를 읽고 있어요…";let doc=try await api.document(file:file,text:text,onProgress:{[weak self] progress in self?.status=progress.message});document=doc;status=useAI ? "AI가 문서 전체를 요약하고 원문 근거를 검증하고 있어요…":"문서의 주요 내용과 원문 근거를 정리하고 있어요…";let result=try await api.analyze(doc:doc,context:context,ai:useAI,consent:useAI && consent,engine:useAI ? "ai":"local",purpose:summaryPurpose.rawValue);report=result
            do{try archive?.remember(document:doc,report:result,context:context);refreshLocal()}catch{self.error="분석은 완료했지만 Mac 기록 저장에 실패했습니다: \(error.localizedDescription)"}
            try? await api.bootstrap();analysesRemaining=api.analysesRemaining;status="분석 완료 · 이 Mac에 기록 저장"
        }catch{self.error=error.localizedDescription;status="분석을 완료하지 못했어요";try? await api.bootstrap();analysesRemaining=api.analysesRemaining}
    }
    func saveTask(_ finding:Finding,title:String,description:String,due:String,approved:Bool) async->Bool{
        guard let archive,let report,let document,!busy else{return false};busy=true;defer{busy=false}
        do{_ = try archive.save(report:report,document:document,finding:finding,title:title,description:description,due:due,approved:approved);refreshLocal();notice="이 Mac의 내 작업에 등록했습니다.";error=nil;return true}catch{self.error=error.localizedDescription;return false}
    }
    func complete(_ task:WorkItem) async{guard let archive,!busy else{return};do{try archive.setStatus(task,done:task.status != "DONE");refreshLocal()}catch{self.error=error.localizedDescription}}
    func registered(_ finding:Finding)->Bool{tasks.contains{$0.analysisId==report?.id && $0.findingId==finding.id}}
    func openHistory(_ item:HistoryEntry){guard !busy else{return};file=nil;text="";context=item.context;consent=false;document=item.document;report=item.report;error=nil;notice="이 Mac에 저장한 결과입니다. 재분석하려면 원본 문서를 다시 선택하세요.";tab="analyze"}
    func exportRecords(){guard let archive,!busy else{return};let p=NSSavePanel();p.nameFieldStringValue="ActionDoc-기록.json";p.message="문서 내용과 입력한 상황이 포함됩니다. 내보낸 파일을 안전하게 보관하세요.";p.allowedContentTypes=[.json];if p.runModal() == .OK,let url=p.url{do{try archive.exportData().write(to:url,options:.atomic);try FileManager.default.setAttributes([.posixPermissions:0o600],ofItemAtPath:url.path);notice="기록을 내보냈습니다."}catch{self.error=error.localizedDescription}}}
    func importRecords(){guard let archive,!busy else{return};let p=NSOpenPanel();p.allowedContentTypes=[.json];p.allowsMultipleSelection=false;p.message="기존 기록을 보존하며 새로운 기록만 추가합니다.";if p.runModal() == .OK,let url=p.url{do{guard (try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0)<=50_000_000 else{throw ServiceError(message:"50MB 이하 기록 파일을 선택해 주세요.")};try archive.importData(Data(contentsOf:url));refreshLocal();notice="기존 기록을 보존하고 가져왔습니다."}catch{self.error=error.localizedDescription}}}
    func openRoute(_ route:ActionRoute){
        guard route.isSourceBacked(by:document),let url=route.validatedURL else{error="이 연결 주소는 지원하지 않거나 올바르지 않습니다. 원문에서 확인해 주세요.";return}
        if !NSWorkspace.shared.open(url){error="주소를 열 수 있는 앱을 찾지 못했습니다. 주소를 복사해 이용해 주세요."}else{notice=route.kind=="WEB" ? "브라우저에서 연결 주소를 열었습니다.":"연결 주소를 처리할 앱을 열었습니다."}
    }
    func copyRoute(_ route:ActionRoute){
        guard route.isSourceBacked(by:document) else{error="연결 주소를 확인해 주세요.";return}
        let value=route.kind=="WEB" ? route.url:route.kind=="PHONE" ? String(route.url.dropFirst(4)):route.displayAddress
        NSPasteboard.general.clearContents();NSPasteboard.general.setString(value,forType:.string);notice="연결 주소를 복사했습니다."
    }
    func saveMarkdown(){guard let document else{return};let p=NSSavePanel();p.nameFieldStringValue="안내문.md";p.allowedContentTypes=[UTType(filenameExtension:"md") ?? .plainText];if p.runModal() == .OK,let url=p.url{do{try document.markdown.write(to:url,atomically:true,encoding:.utf8)}catch{self.error=error.localizedDescription}}}
}
