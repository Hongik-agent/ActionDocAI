import SwiftUI

struct TaskReviewView:View {
    @ObservedObject var model:AppModel
    let finding:Finding
    var body:some View {
        VStack(alignment:.leading,spacing:14) {
            HStack{Text("확인한 조치를 작업으로").font(.title2).fontWeight(.bold);Spacer();Button("닫기"){model.selectedFinding=nil}.disabled(model.busy)}
            Text("제목과 기한을 확인하고 필요한 내용을 수정하세요.").font(.caption).foregroundStyle(.secondary)
            VStack(alignment:.leading,spacing:6){Text("작업 제목").font(.caption);TextField("작업 제목",text:$model.reviewTitle).textFieldStyle(.roundedBorder)}
            VStack(alignment:.leading,spacing:6){Text("작업 내용").font(.caption);TextEditor(text:$model.reviewDescription).font(.callout).frame(height:85).padding(5).overlay(RoundedRectangle(cornerRadius:6).stroke(Color.gray.opacity(0.25)))}
            VStack(alignment:.leading,spacing:6){Text("처리 기한 · 선택").font(.caption);TextField("YYYY-MM-DD · 모르면 비워 두세요",text:$model.reviewDue).textFieldStyle(.roundedBorder)}
            ScrollView{Text(finding.quote).font(.caption).foregroundStyle(.secondary).textSelection(.enabled).frame(maxWidth:.infinity,alignment:.leading)}.frame(maxHeight:85).padding(12).background(Color.gray.opacity(0.06),in:RoundedRectangle(cornerRadius:8))
            if model.document?.ocrPages?.contains(finding.page)==true { Text("이 근거는 OCR 인식 결과입니다. 기한·금액을 원본 이미지와 확인해 주세요.").font(.caption2).foregroundStyle(.orange) }
            Toggle("원문과 작업 내용을 확인했으며 등록에 동의합니다.",isOn:$model.reviewApproved).toggleStyle(.checkbox).font(.caption)
            if let error=model.error { Text(error).font(.caption).foregroundStyle(.red) }
            Button{Task{if await model.saveTask(finding,title:model.reviewTitle,description:model.reviewDescription,due:model.reviewDue,approved:model.reviewApproved){model.selectedFinding=nil}}}label:{Text(model.busy ? "등록하고 있어요…":"확인한 작업 등록").frame(maxWidth:.infinity)}.buttonStyle(.borderedProminent).controlSize(.large).disabled(!model.reviewApproved || model.reviewTitle.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty || model.busy)
        }.padding(25).frame(width:420).onAppear{model.reviewApproved=false;model.error=nil;model.reviewTitle=finding.title=="필요한 조치 검토" ? "안내문 조치 확인 및 처리":finding.title;model.reviewDescription=finding.detail;model.reviewDue=finding.date ?? ""}
    }
}
struct SourceView:View {
    @ObservedObject var model:AppModel
    var body:some View {
        VStack(alignment:.leading,spacing:15) {
            HStack{Text("원문과 근거 확인").font(.title2).fontWeight(.bold);Spacer();Button("닫기"){model.showSource=false}}
            Text(model.document?.name ?? "안내문").font(.caption).foregroundStyle(.secondary)
            ScrollView { LazyVStack(alignment:.leading,spacing:13){ForEach(model.document?.blocks ?? []){block in VStack(alignment:.leading,spacing:4){Text("\(SourceLocation.page(block.page)) · \(block.id)" + (model.document?.ocrPages?.contains(block.page)==true ? " · OCR":"")).font(.caption2).foregroundStyle(.secondary);Text(block.text).font(.callout).textSelection(.enabled).frame(maxWidth:.infinity,alignment:.leading)}.padding(12).background(Color.gray.opacity(0.04),in:RoundedRectangle(cornerRadius:8))}} }
            HStack{Button("마크다운 저장"){model.saveMarkdown()};Spacer();if let file=model.file { Button("원본 파일 열기"){NSWorkspace.shared.open(file)} }}.font(.caption)
        }.padding(25).frame(width:450,height:560)
    }
}
