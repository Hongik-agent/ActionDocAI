import AppKit
import Foundation
let directory=URL(fileURLWithPath:CommandLine.arguments[1],isDirectory:true)
try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
for size in [16,32,128,256,512] {
    for scale in [1,2] {
        let pixels=size*scale
        let image=NSImage(size:NSSize(width:pixels,height:pixels))
        image.lockFocus()
        let rect=NSRect(x:Double(pixels)*0.06,y:Double(pixels)*0.06,width:Double(pixels)*0.88,height:Double(pixels)*0.88)
        NSColor(srgbRed:0.23,green:0.34,blue:0.85,alpha:1).setFill()
        NSBezierPath(roundedRect:rect,xRadius:Double(pixels)*0.2,yRadius:Double(pixels)*0.2).fill()
        let symbol=NSImage(systemSymbolName:"doc.text.magnifyingglass",accessibilityDescription:nil)!.withSymbolConfiguration(.init(pointSize:Double(pixels)*0.49,weight:.medium))!
        let tinted=NSImage(size:symbol.size);tinted.lockFocus();NSColor.white.setFill();NSRect(origin:.zero,size:symbol.size).fill();symbol.draw(at:.zero,from:.zero,operation:.destinationIn,fraction:1);tinted.unlockFocus()
        tinted.draw(in:NSRect(x:Double(pixels)*0.25,y:Double(pixels)*0.24,width:Double(pixels)*0.5,height:Double(pixels)*0.52))
        image.unlockFocus()
        let bitmap=NSBitmapImageRep(data:image.tiffRepresentation!)!
        let png=bitmap.representation(using:.png,properties:[:])!
        let name="icon_\(size)x\(size)\(scale==2 ? "@2x":"").png"
        try png.write(to:directory.appendingPathComponent(name))
    }
}
// ICNS is a length-prefixed container of PNG representations.
var payload=Data()
for (tag,name) in [("icp4","icon_16x16.png"),("icp5","icon_32x32.png"),("icp6","icon_32x32@2x.png"),("ic07","icon_128x128.png"),("ic08","icon_256x256.png"),("ic09","icon_512x512.png"),("ic10","icon_512x512@2x.png")] {
    let png=try Data(contentsOf:directory.appendingPathComponent(name))
    payload.append(Data(tag.utf8));var length=UInt32(png.count+8).bigEndian
    withUnsafeBytes(of:&length){payload.append(contentsOf:$0)};payload.append(png)
}
var container=Data("icns".utf8);var total=UInt32(payload.count+8).bigEndian
withUnsafeBytes(of:&total){container.append(contentsOf:$0)};container.append(payload)
try container.write(to:directory.deletingLastPathComponent().appendingPathComponent("AppIcon.icns"))
