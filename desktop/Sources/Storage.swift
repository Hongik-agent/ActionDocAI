import Foundation
import SQLite3
import Darwin

struct HistoryEntry:Codable,Identifiable {let id:String;let document:SourceDocument;let report:AnalysisReport;let context:String;let savedAt:String}
struct ArchiveData:Codable {var version=1;var tasks:[WorkItem]=[];var history:[HistoryEntry]=[]}
@MainActor final class LocalArchive {
    let directory:URL;var file:URL{directory.appendingPathComponent("records.json")}
    private(set) var data=ArchiveData()
    init(directory:URL?=nil)throws{
        self.directory=directory ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/ActionDoc AI",isDirectory:true)
        try FileManager.default.createDirectory(at:self.directory,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700]);try FileManager.default.setAttributes([.posixPermissions:0o700],ofItemAtPath:self.directory.path)
        if FileManager.default.fileExists(atPath:file.path){data=try decode(Data(contentsOf:file));try FileManager.default.setAttributes([.posixPermissions:0o600],ofItemAtPath:file.path)}
        else{var initial=ArchiveData();initial.tasks=try legacyTasks();try commit(initial)}
    }
    private func decode(_ bytes:Data)throws->ArchiveData{
        guard bytes.count<=50_000_000 else{throw ServiceError(message:"기록 파일이 50MB를 넘었습니다. 원본을 보존한 상태로 기록을 확인해 주세요.")}
        let result:ArchiveData;do{result=try JSONDecoder().decode(ArchiveData.self,from:bytes)}catch{throw ServiceError(message:"기록 파일을 읽지 못했습니다. 원본 파일은 변경하지 않았습니다.")}
        guard result.version==1,Set(result.tasks.map(\.id)).count==result.tasks.count,Set(result.history.map(\.id)).count==result.history.count else{throw ServiceError(message:"지원하지 않거나 중복된 기록 형식입니다. 원본은 변경하지 않았습니다.")}
        for task in result.tasks{try validate(title:task.title,description:task.description,due:task.dueDate);guard !task.id.isEmpty,["TODO","DONE"].contains(task.status) else{throw ServiceError(message:"작업 기록 형식을 확인해 주세요.")}}
        for h in result.history{guard h.id==h.report.id,h.report.documentId==h.document.id else{throw ServiceError(message:"분석과 원문 기록의 연결을 확인해 주세요.")}}
        return result
    }
    private func commit(_ next:ArchiveData)throws{
        let bytes=try JSONEncoder().encode(next);guard bytes.count<=50_000_000 else{throw ServiceError(message:"기록 파일이 50MB를 넘었습니다. 내보내기로 백업한 뒤 기록을 정리해 주세요.")}
        let temporary=directory.appendingPathComponent(".records-\(UUID().uuidString).tmp");defer{try? FileManager.default.removeItem(at:temporary)}
        guard FileManager.default.createFile(atPath:temporary.path,contents:bytes,attributes:[.posixPermissions:0o600]),rename(temporary.path,file.path)==0 else{throw ServiceError(message:"Mac에 기록을 저장하지 못했습니다. 저장 공간과 폴더 권한을 확인해 주세요.")}
        data=next
    }
    func remember(document:SourceDocument,report:AnalysisReport,context:String)throws{
        guard document.id==report.documentId else{throw ServiceError(message:"분석 결과와 문서가 일치하지 않습니다.")}
        var next=data;let item=HistoryEntry(id:report.id,document:document,report:report,context:context,savedAt:ISO8601DateFormatter().string(from:Date()))
        if let i=next.history.firstIndex(where:{$0.id==item.id}){next.history[i]=item}else{next.history.insert(item,at:0)};try commit(next)
    }
    private func validate(title:String,description:String,due:String?)throws{
        guard !title.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty,title.count<=160,description.count<=4000 else{throw ServiceError(message:"제목은 1~160자, 내용은 4,000자 이하여야 합니다.")}
        if let due,!due.isEmpty{let f=DateFormatter();f.locale=Locale(identifier:"en_US_POSIX");f.calendar=Calendar(identifier:.gregorian);f.timeZone=TimeZone(secondsFromGMT:0);f.dateFormat="yyyy-MM-dd";f.isLenient=false;guard let parsed=f.date(from:due),f.string(from:parsed)==due else{throw ServiceError(message:"올바른 기한을 입력해 주세요.")}}
    }
    func save(report:AnalysisReport,document:SourceDocument,finding:Finding,title:String,description:String,due:String,approved:Bool)throws->WorkItem{
        guard approved else{throw ServiceError(message:"원문 확인과 등록 승인이 필요합니다.")}
        guard report.documentId==document.id,finding.category=="action",report.findings.contains(where:{$0.id==finding.id && $0.quote==finding.quote}) else{throw ServiceError(message:"유효한 추천 조치를 확인해 주세요.")}
        try validate(title:title,description:description,due:due)
        if let old=data.tasks.first(where:{$0.analysisId==report.id && $0.findingId==finding.id}){return old}
        let item=WorkItem(id:UUID().uuidString,analysisId:report.id,findingId:finding.id,title:title.trimmingCharacters(in:.whitespacesAndNewlines),description:description,dueDate:due.isEmpty ? nil:due,status:"TODO",sourceName:document.name,quote:finding.quote,page:finding.page,createdAt:ISO8601DateFormatter().string(from:Date()))
        var next=data;next.tasks.insert(item,at:0);try commit(next);return item
    }
    func setStatus(_ item:WorkItem,done:Bool)throws{
        guard let i=data.tasks.firstIndex(where:{$0.id==item.id})else{throw ServiceError(message:"작업을 찾지 못했습니다.")}
        let old=data.tasks[i];var next=data;next.tasks[i]=WorkItem(id:old.id,analysisId:old.analysisId,findingId:old.findingId,title:old.title,description:old.description,dueDate:old.dueDate,status:done ? "DONE":"TODO",sourceName:old.sourceName,quote:old.quote,page:old.page,createdAt:old.createdAt);try commit(next)
    }
    func exportData()throws->Data{try JSONEncoder().encode(data)}
    func importData(_ bytes:Data)throws{
        let incoming=try decode(bytes);var next=data;let taskIds=Set(next.tasks.map(\.id));let historyIds=Set(next.history.map(\.id));let taskKeys=Set(next.tasks.map{$0.analysisId+"\u{0}"+$0.findingId});next.tasks += incoming.tasks.filter{!taskIds.contains($0.id) && !taskKeys.contains($0.analysisId+"\u{0}"+$0.findingId)};next.history += incoming.history.filter{!historyIds.contains($0.id)};try commit(next)
    }
    // Read an older installation once. Keep its SQLite file unchanged as a backup.
    private func legacyTasks()throws->[WorkItem]{
        let old=directory.appendingPathComponent("actiondoc.db");guard FileManager.default.fileExists(atPath:old.path)else{return []}
        var db:OpaquePointer?;guard sqlite3_open_v2(old.path,&db,SQLITE_OPEN_READONLY,nil)==SQLITE_OK else{if let db{sqlite3_close(db)};throw ServiceError(message:"이전 작업을 읽지 못했습니다. 기존 DB는 보존했습니다.")};defer{sqlite3_close(db)}
        var query:OpaquePointer?;let sql="SELECT id,analysis_id,finding_id,title,description,due_date,status,source_name,quote,page,created_at FROM tasks ORDER BY created_at DESC"
        guard sqlite3_prepare_v2(db,sql,-1,&query,nil)==SQLITE_OK else{throw ServiceError(message:"이전 작업 형식을 읽지 못했습니다. 기존 DB는 보존했습니다.")};defer{sqlite3_finalize(query)}
        func text(_ n:Int32)->String{guard let value=sqlite3_column_text(query,n)else{return ""};return String(cString:value)}
        var tasks:[WorkItem]=[];var step=sqlite3_step(query)
        while step==SQLITE_ROW{tasks.append(WorkItem(id:text(0),analysisId:text(1),findingId:text(2),title:text(3),description:text(4),dueDate:text(5).isEmpty ? nil:text(5),status:text(6),sourceName:text(7),quote:text(8),page:Int(sqlite3_column_int(query,9)),createdAt:text(10)));step=sqlite3_step(query)}
        guard step==SQLITE_DONE else{throw ServiceError(message:"이전 작업을 끝까지 읽지 못했습니다. 기존 DB는 보존했습니다.")};return tasks
    }
}
