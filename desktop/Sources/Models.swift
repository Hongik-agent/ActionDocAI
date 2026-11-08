import Foundation

enum UploadLimits {
    static let maxPdfPages=50
    static let maxFileMB=50
    static let maxFileBytes=maxFileMB*1024*1024
}

enum SummaryPurpose:String,CaseIterable,Identifiable {
    case general="GENERAL", business="BUSINESS", support="SUPPORT", contract="CONTRACT"
    var id:String{rawValue}
    var label:String{switch self{case .general:return "전체 정리";case .business:return "공문·업무 요청";case .support:return "지원사업·모집 공고";case .contract:return "요금·계약 안내"}}
    var hint:String{switch self{case .general:return "원문의 주요 문장과 조건을 항목별로 정리합니다.";case .business:return "요청 사항, 제출 자료, 기한과 담당 부서를 확인합니다.";case .support:return "지원 대상, 지원 내용, 신청 일정과 서류를 확인합니다.";case .contract:return "요금, 변경 내용, 갱신·해지와 예외를 확인합니다."}}
}

enum SourceLocation {
    static func page(_ number:Int)->String { number > 0 ? "\(number)페이지" : "본문" }
    static func pages(_ numbers:[Int])->String {
        let known=Array(Set(numbers.filter{$0 > 0})).sorted().map(String.init)
        return known.isEmpty ? "본문" : known.joined(separator:", ")+"페이지"
    }
    static func count(_ number:Int)->String { number > 0 ? "\(number)페이지" : "쪽 번호 없음" }
}

struct SourceBlock: Codable, Identifiable { let id: String; let page: Int; let text: String }
struct DocumentProgress:Decodable {let phase:String;let completedPages:Int;let totalPages:Int;let message:String}
struct SourceDocument: Codable, Identifiable {
    let id: String; let name: String; let type: String; let pages: Int; let characters: Int
    let markdown: String; let blocks: [SourceBlock]; let warnings: [String];let ocrPages:[Int]?
}
struct Finding: Codable, Identifiable {
    let id: String; let category: String; let title: String; let detail: String
    let quote: String; let blockId: String; let page: Int; let date: String?
    var label: String { ["change":"변경 조건","fee":"요금·금액","date":"주요 날짜","action":"할 일","impact":"미처리 영향"][category] ?? category }
    var symbol: String { ["change":"arrow.triangle.2.circlepath","fee":"wonsign.circle","date":"calendar","action":"checkmark.circle","impact":"exclamationmark.triangle"][category] ?? "doc.text" }
}
struct ActionRoute:Codable,Identifiable {
    let id:String;let kind:String;let title:String;let url:String;let displayAddress:String;let hint:String;let navigation:String;let status:String;let applicationEndsOn:String?;let evidence:[Evidence]
    var expired:Bool{
        if status=="REFERENCE"{return true}
        guard let end=applicationEndsOn else{return false}
        let format=DateFormatter();format.locale=Locale(identifier:"en_US_POSIX");format.calendar=Calendar(identifier:.gregorian);format.timeZone=TimeZone(identifier:"Asia/Seoul");format.dateFormat="yyyy-MM-dd"
        return end < format.string(from:Date())
    }
    func isSourceBacked(by document:SourceDocument?)->Bool{
        guard let document,!displayAddress.isEmpty,!evidence.isEmpty,let target=validatedURL,evidence.allSatisfy({source in document.blocks.contains(where:{$0.id==source.blockId && $0.page==source.page && $0.text.contains(source.quote)})}),evidence.contains(where:{$0.quote.contains(displayAddress)})else{return false}
        switch kind {
        case "WEB":
            let raw=displayAddress.lowercased().hasPrefix("https://") || displayAddress.lowercased().hasPrefix("http://") ? displayAddress:"https://"+displayAddress
            guard let source=URLComponents(string:raw),let opened=URLComponents(url:target,resolvingAgainstBaseURL:false)else{return false}
            return source.scheme?.lowercased()==opened.scheme?.lowercased() && source.host?.lowercased()==opened.host?.lowercased() && source.port==opened.port && (source.percentEncodedPath.isEmpty ? "/":source.percentEncodedPath)==opened.percentEncodedPath && source.percentEncodedQuery==opened.percentEncodedQuery && source.percentEncodedFragment==opened.percentEncodedFragment
        case "EMAIL":return url=="mailto:"+displayAddress.replacingOccurrences(of:"%",with:"%25")
        case "PHONE":let first=displayAddress.components(separatedBy:CharacterSet(charactersIn:"~～")).first ?? "";return url=="tel:"+first.filter{$0.isNumber || $0=="+"}
        default:return false
        }
    }
    var validatedURL:URL?{
        guard let target=URL(string:url),let components=URLComponents(url:target,resolvingAgainstBaseURL:false),let scheme=components.scheme?.lowercased(),!url.unicodeScalars.contains(where:{CharacterSet.controlCharacters.contains($0)})else{return nil}
        switch kind {
        case "WEB":
            guard ["https","http"].contains(scheme),let host=components.host?.lowercased(),host.contains("."),!host.hasSuffix("."),components.user==nil,components.password==nil,[nil,80,443].contains(components.port),host.range(of:"^[0-9.]+$",options:.regularExpression)==nil,!host.contains(":"),![".local",".internal",".localhost",".test"].contains(where:host.hasSuffix) else{return nil}
        case "EMAIL":
            let address=components.path.removingPercentEncoding ?? components.path
            guard scheme=="mailto",components.query==nil,components.fragment==nil,address.contains("@"),!address.unicodeScalars.contains(where:{CharacterSet.controlCharacters.contains($0)}),!address.contains("%")else{return nil}
        case "PHONE":
            guard scheme=="tel",components.query==nil,components.fragment==nil,components.path.range(of:"^\\+?[0-9]{5,15}$",options:.regularExpression) != nil else{return nil}
        default:return nil
        }
        return target
    }
}
struct Evidence:Codable {let blockId:String;let page:Int;let quote:String}
struct ReportSection:Codable,Identifiable {let id:String;let title:String;let body:String;let evidence:[Evidence];let aiRefined:Bool}
struct PersonalCheck:Codable,Identifiable {let id:String;let criterion:String;let status:String;let reason:String;let profileQuote:String;let evidence:[Evidence]}
struct ExtractedFact:Codable,Identifiable {let id:String;let category:String;let topic:String;let statement:String;let evidence:[Evidence]}
struct PipelineStage:Codable {let name:String;let status:String;let detail:String}
struct AnalysisReport: Codable, Identifiable {
    let id: String; let documentId: String; let title: String; let mode: String; let decision: String
    let findings: [Finding]; let questions: [String]; let warnings: [String]; let chunks: Int; let createdAt: String
    let documentType:String?;let overview:String?;let sections:[ReportSection]?;let periodStatus:String?;let personalChecks:[PersonalCheck]?;let facts:[ExtractedFact]?;let stages:[PipelineStage]?
    var actionRoutes:[ActionRoute]?=nil
}
struct WorkItem: Codable, Identifiable {
    let id: String; let analysisId: String; let findingId: String; let title: String
    let description: String; let dueDate: String?; let status: String
    let sourceName: String; let quote: String; let page: Int; let createdAt: String
}
struct ServiceConfig:Codable {let csrf:String;let aiConfigured:Bool;let hosted:Bool?;let anonymous:Bool?;let storage:String?;let analysesRemaining:Int?}
struct ServiceError: LocalizedError {
    let message: String
    let status:Int?
    init(message:String,status:Int?=nil){self.message=message;self.status=status}
    var errorDescription: String? { message }
}
let sampleNotice = """
통신 요금 변경 및 할인 신청 안내 (비식별 샘플)
2026년 12월 1일부터 월 이용요금이 기존 33,000원에서 36,300원으로 변경됩니다. 두 요금 모두 부가세 포함입니다.
라이트 5G 요금제 가입자만 적용 대상입니다.
2026년 11월 25일까지 고객센터에 장기고객 할인 신청서를 제출해 주세요.
가입 기간 24개월 이상이면 월 3,300원의 할인을 받을 수 있습니다.
신청서를 제출하지 않으면 할인이 적용되지 않아 기본 요금이 청구됩니다.
"""
