import Foundation
import Vision
import ImageIO

struct OCRLine:Codable { let text:String;let confidence:Float }
struct OCRResult:Codable { let text:String;let confidence:Double;let lowConfidenceLines:Int;let languages:[String];let lines:[OCRLine] }
@main struct OCRHelper {
    static func main() {
        guard CommandLine.arguments.count==3 else { fputs("Usage: actiondoc-ocr image.png result.json\n",stderr);exit(2) }
        let image=URL(fileURLWithPath:CommandLine.arguments[1]),output=URL(fileURLWithPath:CommandLine.arguments[2])
        var phase="initialization"
        do {
            let request=VNRecognizeTextRequest();request.recognitionLevel = .accurate;request.usesLanguageCorrection=true
            phase="language support"
            let supported=try request.supportedRecognitionLanguages()
            let languages=["ko-KR","en-US"].filter{supported.contains($0)}
            guard languages.contains("ko-KR") else { throw NSError(domain:"ActionDocOCR",code:1,userInfo:[NSLocalizedDescriptionKey:"Korean OCR is unavailable on this macOS version."]) }
            request.recognitionLanguages=languages
            request.minimumTextHeight=0.003
            let handler=VNImageRequestHandler(url:image,options:[:])
            phase="text recognition"
            do { try handler.perform([request]) }
            catch { request.usesCPUOnly=true;try handler.perform([request]) }
            let lines=(request.results ?? []).compactMap { observation -> OCRLine? in
                guard let candidate=observation.topCandidates(1).first else{return nil}
                let text=candidate.string.trimmingCharacters(in:.whitespacesAndNewlines)
                return text.isEmpty ? nil:OCRLine(text:text,confidence:candidate.confidence)
            }
            let total=lines.reduce(0){$0+$1.text.count}
            let confidence=total==0 ? 0:lines.reduce(0.0){$0+Double($1.confidence)*Double($1.text.count)}/Double(total)
            let result=OCRResult(text:lines.map(\.text).joined(separator:"\n"),confidence:confidence,lowConfidenceLines:lines.filter{$0.confidence<0.7}.count,languages:languages,lines:lines)
            try JSONEncoder().encode(result).write(to:output,options:.atomic)
        }catch{fputs("Local OCR failed (\(phase)): \(error.localizedDescription)\n",stderr);exit(1)}
    }
}
