import Foundation

@MainActor final class APIClient {
    let base:URL
    let desktopToken:String
    private let session:URLSession
    private var csrf=""
    private(set) var aiConfigured=false
    private(set) var hosted=false
    private(set) var analysesRemaining:Int?
    init(base:URL,token:String=""){
        self.base=base;desktopToken=token
        let configuration=URLSessionConfiguration.ephemeral;configuration.httpShouldSetCookies=true;configuration.timeoutIntervalForRequest=100;configuration.timeoutIntervalForResource=1500
        session=URLSession(configuration:configuration)
    }
    func bootstrap() async throws {
        let data=try await request("/api/config");let config=try JSONDecoder().decode(ServiceConfig.self,from:data)
        csrf=config.csrf;aiConfigured=config.aiConfigured;hosted=config.hosted ?? true;analysesRemaining=config.analysesRemaining
    }
    func request(_ path:String,method:String="GET",body:Data?=nil,type:String="application/json") async throws -> Data {
        var req=URLRequest(url:base.appendingPathComponent(path));req.httpMethod=method;req.httpBody=body
        if path=="/api/documents" { req.timeoutInterval=600 }
        if path=="/api/analyses" { req.timeoutInterval=1500 }
        req.setValue(desktopToken,forHTTPHeaderField:"X-ActionDoc-Desktop")
        if method != "GET" { req.setValue(csrf,forHTTPHeaderField:"X-CSRF-Token");req.setValue(type,forHTTPHeaderField:"Content-Type") }
        let (data,response)=try await session.data(for:req)
        guard let http=response as? HTTPURLResponse else { throw ServiceError(message:"분석 엔진 응답을 읽지 못했습니다.") }
        guard (200...299).contains(http.statusCode) else {
            let message=(try? JSONSerialization.jsonObject(with:data) as? [String:Any])?["error"] as? String
            throw ServiceError(message:message ?? "요청에 실패했습니다 (\(http.statusCode)).",status:http.statusCode)
        }
        return data
    }
    func document(file:URL?,text:String,onProgress:((DocumentProgress)->Void)?=nil) async throws -> SourceDocument {
        try await bootstrap()
        let boundary="ActionDoc-\(UUID().uuidString)";var body=Data()
        func append(_ text:String) { body.append(Data(text.utf8)) }
        let progressId=UUID().uuidString
        append("--\(boundary)\r\nContent-Disposition: form-data; name=\"progressId\"\r\n\r\n\(progressId)\r\n")
        if let file {
            let resource=try file.resourceValues(forKeys:[.fileSizeKey,.isRegularFileKey])
            guard resource.isRegularFile==true,(resource.fileSize ?? 0)<=UploadLimits.maxFileBytes else { throw ServiceError(message:"\(UploadLimits.maxFileMB)MB 이하의 일반 문서 파일을 선택해 주세요.") }
            let data=try Data(contentsOf:file)
            let name=file.lastPathComponent.replacingOccurrences(of:"\"",with:"_").replacingOccurrences(of:"\r",with:"_").replacingOccurrences(of:"\n",with:"_")
            append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(name)\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            body.append(data);append("\r\n")
        } else {
            append("--\(boundary)\r\nContent-Disposition: form-data; name=\"text\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n\(text)\r\n")
        }
        append("--\(boundary)--\r\n")
        let poller=Task { @MainActor in
            while !Task.isCancelled {
                do {
                    let data=try await request("/api/documents/progress/"+progressId)
                    guard !Task.isCancelled else{return}
                    if let progress=try? JSONDecoder().decode(DocumentProgress.self,from:data){onProgress?(progress)}
                }catch{}
                do{try await Task.sleep(nanoseconds:750_000_000)}catch{return}
            }
        }
        defer{poller.cancel()}
        return try JSONDecoder().decode(SourceDocument.self,from:await request("/api/documents",method:"POST",body:body,type:"multipart/form-data; boundary=\(boundary)"))
    }
    func analyze(doc:SourceDocument,context:String,ai:Bool,consent:Bool,engine:String?=nil,purpose:String="GENERAL") async throws -> AnalysisReport {
        try await bootstrap()
        let payload:[String:Any]=["documentId":doc.id,"context":context,"mode":engine ?? (ai ? "ai":"local"),"consent":consent,"purpose":purpose]
        let body=try JSONSerialization.data(withJSONObject:payload)
        return try JSONDecoder().decode(AnalysisReport.self,from:await request("/api/analyses",method:"POST",body:body))
    }
}
