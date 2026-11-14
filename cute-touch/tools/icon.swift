// Renders the app icon (the menu-bar cat on a soft squircle) to a 1024px PNG.
import AppKit
import SwiftUI

@main struct IconRenderer {
    @MainActor static func main() {
        let cat = PetModel()
        let view = ZStack {
            RoundedRectangle(cornerRadius: 185, style: .continuous)
                .fill(LinearGradient(colors: [Color(red: 1.0, green: 0.86, blue: 0.9), Color(red: 0.84, green: 0.78, blue: 1.0)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 824, height: 824)
            PetFace(cat: cat).scaleEffect(22).offset(y: 10)
        }
        .frame(width: 1024, height: 1024)
        let r = ImageRenderer(content: view)
        r.scale = 1
        let rep = NSBitmapImageRep(cgImage: r.cgImage!)
        try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
    }
}
