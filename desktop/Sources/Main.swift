import AppKit
import SwiftUI
import Carbon

@MainActor final class AppDelegate:NSObject,NSApplicationDelegate,NSWindowDelegate {
    let model=AppModel()
    var window:NSWindow!
    var statusItem:NSStatusItem!
    var hotKey:EventHotKeyRef?
    var eventHandler:EventHandlerRef?
    var isSelfTest=CommandLine.arguments.contains("--self-test")
    func applicationDidFinishLaunching(_ notification:Notification) {
        if isSelfTest {
            Task { do { try await DesktopSelfTest.run();print("DESKTOP_SELF_TEST_PASSED");NSApp.terminate(nil) } catch { fputs("DESKTOP_SELF_TEST_FAILED: \(error.localizedDescription)\n",stderr);exit(1) } }
            return
        }
        let size=NSSize(width:510,height:740)
        window=NSWindow(contentRect:NSRect(origin:.zero,size:size),styleMask:[.titled,.closable,.miniaturizable,.resizable],backing:.buffered,defer:false)
        window.title="ActionDoc AI";window.minSize=NSSize(width:480,height:640);window.isReleasedWhenClosed=false;window.delegate=self
        window.contentView=NSHostingView(rootView:DesktopView(model:model));window.center()
        createMenu();registerShortcut();showWindow()
        Task { await model.boot() }
    }
    func createMenu() {
        let main=NSMenu();let appMenu=NSMenu();let appItem=NSMenuItem();appItem.submenu=appMenu;main.addItem(appItem)
        appMenu.addItem(withTitle:"ActionDoc 열기",action:#selector(showWindow),keyEquivalent:"1").target=self
        appMenu.addItem(.separator());appMenu.addItem(withTitle:"ActionDoc 종료",action:#selector(quit),keyEquivalent:"q").target=self
        let edit=NSMenuItem(title:"편집",action:nil,keyEquivalent:"");let editMenu=NSMenu(title:"편집")
        for (title,selector,key) in [("실행 취소","undo:","z"),("잘라내기","cut:","x"),("복사","copy:","c"),("붙여넣기","paste:","v"),("전체 선택","selectAll:","a")] { editMenu.addItem(withTitle:title,action:NSSelectorFromString(selector),keyEquivalent:key) }
        edit.submenu=editMenu;main.addItem(edit);NSApp.mainMenu=main
        statusItem=NSStatusBar.system.statusItem(withLength:NSStatusItem.squareLength)
        if let button=statusItem.button { button.image=NSImage(systemSymbolName:"doc.text.magnifyingglass",accessibilityDescription:"ActionDoc AI");button.toolTip="ActionDoc AI · ⌃⌥Space" }
        let menu=NSMenu()
        menu.addItem(withTitle:"ActionDoc 열기     ⌃⌥Space",action:#selector(showWindow),keyEquivalent:"").target=self
        menu.addItem(withTitle:"복사한 내용 붙여넣기",action:#selector(pasteClipboard),keyEquivalent:"").target=self
        menu.addItem(withTitle:"파일 선택…",action:#selector(pickFile),keyEquivalent:"").target=self
        menu.addItem(.separator());menu.addItem(withTitle:"내 작업",action:#selector(showTasks),keyEquivalent:"").target=self
        menu.addItem(.separator());menu.addItem(withTitle:"종료",action:#selector(quit),keyEquivalent:"").target=self
        statusItem.menu=menu
    }
    @objc func showWindow(){window?.makeKeyAndOrderFront(nil);NSApp.activate(ignoringOtherApps:true)}
    @objc func pasteClipboard(){showWindow();model.paste()}
    @objc func pickFile(){showWindow();model.pickFile()}
    @objc func showTasks(){showWindow();model.tab="tasks";Task{await model.refreshTasks()}}
    @objc func quit(){NSApp.terminate(nil)}
    func windowShouldClose(_ sender:NSWindow)->Bool{sender.orderOut(nil);return false}
    func applicationShouldTerminateAfterLastWindowClosed(_ sender:NSApplication)->Bool{false}
    func applicationShouldHandleReopen(_ sender:NSApplication,hasVisibleWindows flag:Bool)->Bool{showWindow();return true}
    func application(_ application:NSApplication,open urls:[URL]){if let url=urls.first{showWindow();model.acceptFile(url)}}
    func applicationWillTerminate(_ notification:Notification){model.server.stop();if let hotKey{UnregisterEventHotKey(hotKey)};if let eventHandler{RemoveEventHandler(eventHandler)}}
    func registerShortcut(){
        var spec=EventTypeSpec(eventClass:OSType(kEventClassKeyboard),eventKind:UInt32(kEventHotKeyPressed))
        let pointer=Unmanaged.passUnretained(self).toOpaque()
        let installed=InstallEventHandler(GetApplicationEventTarget(),{_,_,context in
            guard let context else{return OSStatus(eventNotHandledErr)}
            let delegate=Unmanaged<AppDelegate>.fromOpaque(context).takeUnretainedValue()
            DispatchQueue.main.async{delegate.showWindow()};return noErr
        },1,&spec,pointer,&eventHandler)
        let id=EventHotKeyID(signature:0x41444341,id:1)
        let result=RegisterEventHotKey(UInt32(kVK_Space),UInt32(controlKey|optionKey),id,GetApplicationEventTarget(),0,&hotKey)
        model.shortcutAvailable=installed==noErr && result==noErr
    }
}

@main struct ActionDocLauncher {
    @MainActor static func main(){
        if CommandLine.arguments.contains("--self-test") {
            Task { @MainActor in
                do { try await DesktopSelfTest.run(); print("DESKTOP_SELF_TEST_PASSED"); exit(0) }
                catch { fputs("DESKTOP_SELF_TEST_FAILED: \(error.localizedDescription)\n",stderr); exit(1) }
            }
            dispatchMain()
        }
        let app=NSApplication.shared
        // Launch Services normally handles this; direct invocation also reopens an existing copy.
        if !CommandLine.arguments.contains("--self-test"),let bundleID=Bundle.main.bundleIdentifier,
           let existing=NSRunningApplication.runningApplications(withBundleIdentifier:bundleID).first(where:{$0.processIdentifier != ProcessInfo.processInfo.processIdentifier}){
            existing.activate(options:[.activateAllWindows]);return
        }
        app.setActivationPolicy(.regular)
        let delegate=AppDelegate();app.delegate=delegate
        withExtendedLifetime(delegate){app.run()}
    }
}
