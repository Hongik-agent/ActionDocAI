import Foundation
import SwiftUI
import Security

struct PersonalSettings:Codable {
    var model="gpt-4.1-mini"
    var connected=false
    static var directory:URL {FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/ActionDoc AI")}
    static var path:URL {directory.appendingPathComponent("personal-settings.json")}
    static func load()->Self{(try? JSONDecoder().decode(Self.self,from:Data(contentsOf:path))) ?? Self()}
    func save()throws{try FileManager.default.createDirectory(at:Self.directory,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700]);try JSONEncoder().encode(self).write(to:Self.path,options:.atomic);try FileManager.default.setAttributes([.posixPermissions:0o600],ofItemAtPath:Self.path.path)}
}
enum PersonalKeychain {
    static var query:[String:Any]{[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:"ai.actiondoc.personal.openai",kSecAttrAccount as String:"openai-api-key"]}
    static func load()throws->String{var q=query;q[kSecReturnData as String]=true;q[kSecMatchLimit as String]=kSecMatchLimitOne;var item:CFTypeRef?;let result=SecItemCopyMatching(q as CFDictionary,&item);if result==errSecItemNotFound{return ""};guard result==errSecSuccess,let data=item as? Data,let key=String(data:data,encoding:.utf8)else{throw ServiceError(message:"키체인에서 API 키를 읽지 못했습니다.")};return key}
    static func save(_ key:String)throws{guard !key.isEmpty else{throw ServiceError(message:"API 키를 입력해 주세요.")};let data=Data(key.utf8);let updated=SecItemUpdate(query as CFDictionary,[kSecValueData as String:data] as CFDictionary);if updated==errSecSuccess{return};guard updated==errSecItemNotFound else{throw ServiceError(message:"키체인 API 키를 갱신하지 못했습니다.")};var q=query;q[kSecValueData as String]=data;q[kSecAttrAccessible as String]=kSecAttrAccessibleWhenUnlocked;guard SecItemAdd(q as CFDictionary,nil)==errSecSuccess else{throw ServiceError(message:"API 키를 키체인에 저장하지 못했습니다.")}}
    static func remove()throws{let result=SecItemDelete(query as CFDictionary);guard result==errSecSuccess || result==errSecItemNotFound else{throw ServiceError(message:"키체인 연결을 해제하지 못했습니다.")}}
}
@MainActor final class PersonalServer {
    private var process:Process?
    private var log:URL?
    func start(key:String,model:String) async throws->APIClient{
        stop()
        guard let resources=Bundle.main.resourceURL else{throw ServiceError(message:"앱 리소스를 찾지 못했습니다.")}
        let java=resources.appendingPathComponent("runtime/bin/java"),jar=resources.appendingPathComponent("server.jar")
        guard FileManager.default.isExecutableFile(atPath:java.path),FileManager.default.fileExists(atPath:jar.path)else{throw ServiceError(message:"앱에 포함된 분석 엔진이 없습니다. 앱 압축을 다시 풀어 주세요.")}
        try FileManager.default.createDirectory(at:PersonalSettings.directory,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
        let token=UUID().uuidString+UUID().uuidString
        let temp=FileManager.default.temporaryDirectory.appendingPathComponent("actiondoc-engine-\(UUID().uuidString).log");log=temp
        FileManager.default.createFile(atPath:temp.path,contents:nil,attributes:[.posixPermissions:0o600]);let handle=try FileHandle(forWritingTo:temp)
        let p=Process();p.executableURL=java;p.arguments=["-Djava.awt.headless=true","-jar",jar.path];p.currentDirectoryURL=PersonalSettings.directory
        p.environment=["PATH":"/usr/bin:/bin","HOME":FileManager.default.homeDirectoryForCurrentUser.path,"LANG":"ko_KR.UTF-8","TMPDIR":FileManager.default.temporaryDirectory.path,"PORT":"0","OPENAI_API_KEY":key,"OPENAI_MODEL":model,"ACTIONDOC_DB":PersonalSettings.directory.appendingPathComponent("personal-engine.db").path,"ACTIONDOC_DESKTOP_TOKEN":token,"ACTIONDOC_OCR_COMMAND":resources.appendingPathComponent("actiondoc-ocr").path]
        p.standardOutput=handle;p.standardError=handle;try p.run();try? handle.close();process=p
        let regex=try NSRegularExpression(pattern:"Tomcat started on port (?:\\(s\\): )?(\\d+)")
        for _ in 0..<450 {
            guard p.isRunning else{stop();throw ServiceError(message:"로컬 분석 엔진이 종료됐습니다.")}
            let text=(try? String(contentsOf:temp,encoding:.utf8)) ?? ""
            if let match=regex.firstMatch(in:text,range:NSRange(text.startIndex...,in:text)),let range=Range(match.range(at:1),in:text),let port=Int(text[range]){
                let client=APIClient(base:URL(string:"http://127.0.0.1:\(port)")!,token:token)
                do{try await client.bootstrap();return client}catch{}
            }
            try await Task.sleep(nanoseconds:100_000_000)
        }
        stop();throw ServiceError(message:"로컬 분석 엔진 시작 시간이 초과됐습니다.")
    }
    func stop(){if let p=process,p.isRunning{p.terminate()};process=nil;if let log{try? FileManager.default.removeItem(at:log)};log=nil}
}
struct PersonalSettingsView:View {
    @ObservedObject var model:AppModel
    var body:some View{
        VStack(alignment:.leading,spacing:16){
            HStack{Text("AI 연결 설정").font(.title2).bold();Spacer();Button("닫기"){model.showSettings=false;model.apiKeyInput=""}.disabled(model.busy)}
            Text("OpenAI API 키를 연결하면 문서와 추가 정보를 AI로 분석합니다. 무료 기본 정리는 키 없이 사용할 수 있습니다. 키를 저장해도 AI 모드를 직접 선택하기 전에는 유료 호출하지 않습니다.").font(.callout).foregroundStyle(.secondary)
            Text(model.aiConfigured ? "API 키가 저장되어 있습니다. 실제 사용 가능 여부는 분석 시 확인합니다.":"현재 API 키가 연결되지 않았습니다.").font(.caption)
            SecureField(model.aiConfigured ? "새 API 키 · 기존 키를 유지하려면 비워 두세요":"OpenAI API 키",text:$model.apiKeyInput).textFieldStyle(.roundedBorder).accessibilityLabel("OpenAI API 키")
            DisclosureGroup("고급 설정 · 모델"){TextField("모델 이름",text:$model.modelName).textFieldStyle(.roundedBorder)}
            Text("API 키 방식은 본인의 API 사용량으로 과금됩니다. ChatGPT 구독 사용량을 자동으로 사용하지 않습니다.").font(.caption).foregroundStyle(.secondary)
            Text("ChatGPT 계정 연결: 현재 앱에서 미지원. 공식 연동 자격 및 등록을 확인한 뒤 제공할 예정입니다.").font(.caption).foregroundStyle(.secondary)
            Link("ChatGPT 계정 연동 안내",destination:URL(string:"https://developers.openai.com/siwc/quickstart")!)
            Text("API 키는 macOS 키체인에 보관합니다. AI 분석을 선택할 때 문서 전송 동의가 필요합니다. 자동 OCR은 Mac에서 처리합니다.").font(.caption).foregroundStyle(.secondary)
            if let error=model.error{Text(error).font(.caption).foregroundStyle(.red)}
            HStack{if model.aiConfigured{Button("AI 연결 해제"){Task{await model.disconnectAI()}}};Spacer();Button(model.busy ? "저장 중…":"저장하고 적용"){Task{await model.saveSettings()}}.buttonStyle(.borderedProminent)}.disabled(model.busy)
        }.padding(25).frame(width:430)
    }
}
