import SwiftUI
struct ActionRoutesView:View {
    @ObservedObject var model:AppModel
    let report:AnalysisReport
    private var routes:[ActionRoute]{report.actionRoutes ?? []}
    var body:some View{
        VStack(alignment:.leading,spacing:12){
            HStack{Label("바로 이어서 할 수 있는 일",systemImage:"arrow.up.forward.app").font(.system(size:15,weight:.bold));Spacer();if !routes.isEmpty{Text("\(routes.count)개 경로").font(.caption).foregroundStyle(.secondary)}}
            if routes.isEmpty{
                Text(report.actionRoutes==nil ? "이전 버전에서 저장한 결과입니다. 원본 문서를 다시 분석하면 연결 경로를 찾을 수 있어요.":"문서에서 바로 열 수 있는 웹 주소나 문의처를 찾지 못했습니다. 원문에서 담당 기관과 신청 경로를 확인해 주세요.").font(.system(size:11)).foregroundStyle(.secondary)
            }else{
                Text("문서에 적힌 주소와 연락처입니다. 버튼을 눌러도 신청서가 자동 제출되거나 문서가 첨부되지 않습니다.").font(.system(size:10)).foregroundStyle(.secondary)
                ForEach(Array(routes.prefix(3))){route in routeCard(route)}
                if routes.count>3 {DisclosureGroup("다른 연결 경로 \(routes.count-3)개 보기"){VStack(alignment:.leading,spacing:10){ForEach(Array(routes.dropFirst(3))){route in routeCard(route)}}.padding(.top,8)}.font(.caption)}

            }
        }.padding(14).frame(maxWidth:.infinity,alignment:.leading).background(Color.indigo.opacity(0.035),in:RoundedRectangle(cornerRadius:12))
    }
    private func routeCard(_ route:ActionRoute)->some View{
                    VStack(alignment:.leading,spacing:9){
                        HStack(alignment:.top){Image(systemName:symbol(route)).foregroundStyle(.indigo);VStack(alignment:.leading,spacing:4){Text(route.expired && route.kind=="WEB" ? "공고·후속 안내 확인":route.title).font(.system(size:12,weight:.semibold));Text(route.kind=="WEB" ? route.url:route.displayAddress).font(.system(size:11)).foregroundStyle(.secondary).textSelection(.enabled).lineLimit(2)};Spacer()}
                        if !route.navigation.isEmpty{VStack(alignment:.leading,spacing:4){Text("사이트 안에서 이동할 경로").font(.system(size:10,weight:.medium));Text(route.navigation).font(.system(size:11)).textSelection(.enabled)}.padding(9).frame(maxWidth:.infinity,alignment:.leading).background(Color.gray.opacity(0.04),in:RoundedRectangle(cornerRadius:7))}
                        Text(route.expired && route.kind=="WEB" ? "이 공고의 접수 기간은 지났습니다. 추가 모집과 이후 안내를 확인하세요.":route.hint).font(.system(size:10)).foregroundStyle(route.expired && route.kind=="WEB" ? Color.orange:Color.secondary)
                        HStack(spacing:10){
                            Button{model.openRoute(route)}label:{Label(buttonLabel(route),systemImage:route.kind=="WEB" ? "arrow.up.right":"arrow.up.forward.app")}.buttonStyle(.borderedProminent).controlSize(.small).disabled(!route.isSourceBacked(by:model.document))
                            Button(route.kind=="PHONE" ? "번호 복사":"주소 복사"){model.copyRoute(route)}.buttonStyle(.bordered).controlSize(.small).disabled(!route.isSourceBacked(by:model.document))
                            Spacer()
                        }
                        EvidenceDisclosure(evidence:route.evidence)
                    }.padding(12).frame(maxWidth:.infinity,alignment:.leading).background(.white,in:RoundedRectangle(cornerRadius:10)).overlay(RoundedRectangle(cornerRadius:10).stroke(Color.indigo.opacity(0.12)))
    }
    private func symbol(_ route:ActionRoute)->String{route.kind=="PHONE" ? "phone":route.kind=="EMAIL" ? "envelope":"globe"}
    private func buttonLabel(_ route:ActionRoute)->String{route.kind=="PHONE" ? "전화 앱 열기":route.kind=="EMAIL" ? "메일 작성 창 열기":route.expired ? "후속 안내 확인":"사이트 열기"}
}
