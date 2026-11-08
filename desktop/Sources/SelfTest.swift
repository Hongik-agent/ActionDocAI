import Foundation
import SQLite3
@MainActor enum DesktopSelfTest {
    static func require(_ condition:Bool,_ message:String)throws{if !condition{throw ServiceError(message:message)}}
    static func run()async throws{
        try require(SourceLocation.page(0)=="본문" && SourceLocation.count(0)=="쪽 번호 없음" && SourceLocation.pages([0])=="본문","HWP evidence invented page numbers")
        try require(SourceLocation.page(3)=="3페이지" && SourceLocation.pages([3,1,3])=="1, 3페이지","PDF page labels changed")
        let temp=FileManager.default.temporaryDirectory.appendingPathComponent("actiondoc-local-test-\(UUID().uuidString)");defer{try? FileManager.default.removeItem(at:temp)}
        let archive=try LocalArchive(directory:temp)
        let largeFile=temp.appendingPathComponent("large.HWP")
        FileManager.default.createFile(atPath:largeFile.path,contents:Data([1]))
        let largeHandle=try FileHandle(forWritingTo:largeFile)
        try largeHandle.truncate(atOffset:50*1024*1024);try largeHandle.close()
        let input=AppModel();input.acceptFile(largeFile)
        try require(input.file==largeFile && input.error==nil,"50MB document was rejected by the Mac app")
        let oversizedFile=temp.appendingPathComponent("oversized.HWP")
        FileManager.default.createFile(atPath:oversizedFile.path,contents:Data([1]))
        let oversizedHandle=try FileHandle(forWritingTo:oversizedFile)
        try oversizedHandle.truncate(atOffset:50*1024*1024+1);try oversizedHandle.close()
        input.acceptFile(oversizedFile)
        try require(input.file==largeFile && input.error?.contains("50MB")==true,"Oversized document replaced the selected file")
        print("PASS: 50MB document selection and oversized-file rejection")
        let block=SourceBlock(id:"b1",page:1,text:"2026년 11월 25일까지 서류를 확인하세요.")
        let document=SourceDocument(id:"doc-test",name:"비식별 테스트 문서",type:"TEXT",pages:1,characters:block.text.count,markdown:block.text,blocks:[block],warnings:[],ocrPages:[])
        let finding=Finding(id:"action-test",category:"action",title:"서류 확인",detail:block.text,quote:block.text,blockId:"b1",page:1,date:"2026-11-25")
        let report=AnalysisReport(id:"analysis-test",documentId:document.id,title:"테스트 결과",mode:"ai",decision:"REVIEW_TASKS",findings:[finding],questions:[],warnings:[],chunks:1,createdAt:"2026-10-31T00:00:00Z",documentType:"NOTICE",overview:"테스트 문서의 확인 사항입니다.",sections:[],periodStatus:"UNKNOWN",personalChecks:[],facts:[],stages:[])
        do{_ = try archive.save(report:report,document:document,finding:finding,title:"확인",description:"",due:"",approved:false);throw ServiceError(message:"Unapproved save accepted")}catch let e as ServiceError{try require(e.message != "Unapproved save accepted","Unapproved save accepted")}
        do{_ = try archive.save(report:report,document:document,finding:finding,title:"확인",description:"",due:"2026-02-30",approved:true);throw ServiceError(message:"Invalid date accepted")}catch let e as ServiceError{try require(e.message != "Invalid date accepted","Invalid date accepted")}
        let task=try archive.save(report:report,document:document,finding:finding,title:"서류 확인",description:block.text,due:"2026-11-25",approved:true)
        let duplicate=try archive.save(report:report,document:document,finding:finding,title:"중복",description:"",due:"",approved:true);try require(task.id==duplicate.id,"Task registration is not idempotent")
        try archive.remember(document:document,report:report,context:"비식별 상황 예시");try archive.setStatus(task,done:true)
        let reopened=try LocalArchive(directory:temp);try require(reopened.data.tasks.count==1 && reopened.data.tasks[0].status=="DONE" && reopened.data.history.count==1,"Offline records did not survive reopening")
        print("PASS: approval, deadline validation, duplicate prevention, offline completion, analysis history and persistence")
        let other=try LocalArchive(directory:temp.appendingPathComponent("import"));try other.importData(reopened.exportData());try other.importData(reopened.exportData());try require(other.data.tasks.count==1 && other.data.history.count==1,"Import duplicates records")
        let before=try Data(contentsOf:other.file);do{try other.importData(Data("invalid".utf8));throw ServiceError(message:"Corrupt data accepted")}catch let e as ServiceError{try require(e.message != "Corrupt data accepted","Corrupt import accepted")};try require(try Data(contentsOf:other.file)==before,"Corrupt import changed existing data")
        let permissions=try FileManager.default.attributesOfItem(atPath:other.file.path)[.posixPermissions] as? Int;try require(permissions==0o600,"Records are not owner-only")
        print("PASS: export/import merge, corrupt-file preservation and private file permissions")
        let legacy=temp.appendingPathComponent("legacy");try FileManager.default.createDirectory(at:legacy,withIntermediateDirectories:true);let legacyFile=legacy.appendingPathComponent("actiondoc.db");var db:OpaquePointer?;guard sqlite3_open(legacyFile.path,&db)==SQLITE_OK else{throw ServiceError(message:"Test DB could not open")}
        let sql="CREATE TABLE tasks(id TEXT,analysis_id TEXT,finding_id TEXT,title TEXT,description TEXT,due_date TEXT,status TEXT,source_name TEXT,quote TEXT,page INTEGER,created_at TEXT); INSERT INTO tasks VALUES('old','old-analysis','old-finding','이전 작업','설명',NULL,'TODO','이전 문서','원문',1,'2026-10-30');"
        guard sqlite3_exec(db,sql,nil,nil,nil)==SQLITE_OK else{sqlite3_close(db);throw ServiceError(message:"Test DB setup failed")};sqlite3_close(db);let original=try Data(contentsOf:legacyFile)
        let migrated=try LocalArchive(directory:legacy);try require(migrated.data.tasks.count==1 && migrated.data.tasks[0].id=="old","Legacy task migration failed");try require(try Data(contentsOf:legacyFile)==original,"Legacy database was changed")
        print("PASS: one-time legacy task migration leaves the original SQLite backup unchanged")
        func route(_ kind:String,_ url:String,_ end:String?=nil)->ActionRoute {ActionRoute(id:"r",kind:kind,title:"source",url:url,displayAddress:url,hint:"",navigation:"",status:"AVAILABLE",applicationEndsOn:end,evidence:[])}
        try require(route("WEB","https://youth.seoul.go.kr/").validatedURL != nil,"Valid website rejected")
        try require(route("EMAIL","mailto:help@example.org").validatedURL != nil,"Valid email rejected")
        try require(route("PHONE","tel:0263580642").validatedURL != nil,"Valid phone rejected")
        for url in ["javascript:alert(1)","file:///tmp/test","http://127.0.0.1/","https://user:pass@example.org/","http://router.local/"] {try require(route("WEB",url).validatedURL==nil,"Unsafe destination accepted")}
        try require(route("EMAIL","mailto:help@example.org?body=secret").validatedURL==nil,"Email content was accepted")
        try require(route("WEB","https://example.org/","2020-01-01").expired,"Archived application did not expire")
        var legacyReport=try JSONSerialization.jsonObject(with:JSONEncoder().encode(report)) as! [String:Any];legacyReport.removeValue(forKey:"actionRoutes")
        let decoded=try JSONDecoder().decode(AnalysisReport.self,from:JSONSerialization.data(withJSONObject:legacyReport));try require(decoded.actionRoutes==nil,"Older records were not compatible")
        let sourceBlock=SourceBlock(id:"url-source",page:1,text:"신청: https://example.org")
        let sourceDoc=SourceDocument(id:"url-doc",name:"url",type:"TEXT",pages:1,characters:sourceBlock.text.count,markdown:sourceBlock.text,blocks:[sourceBlock],warnings:[],ocrPages:[])
        let linked=ActionRoute(id:"link",kind:"WEB",title:"source",url:"https://example.org/",displayAddress:"https://example.org",hint:"",navigation:"",status:"AVAILABLE",applicationEndsOn:nil,evidence:[Evidence(blockId:sourceBlock.id,page:1,quote:sourceBlock.text)])
        try require(linked.isSourceBacked(by:sourceDoc),"Source-backed destination was rejected")
        let forged=ActionRoute(id:"link",kind:"WEB",title:"source",url:"https://other.example.org/",displayAddress:"https://example.org",hint:"",navigation:"",status:"AVAILABLE",applicationEndsOn:nil,evidence:linked.evidence)
        try require(!forged.isSourceBacked(by:sourceDoc),"Destination not matching its source was accepted")
        print("PASS: source route URL validation, dynamic expiry and older report compatibility")

    }
}
