// Renders the menu-bar cat in its states to a PNG (the live cat is hidden from screenshots).
import AppKit
import SwiftUI

@main struct CatPreview {
    @MainActor static func main() {
        func cat(_ kind: PetKind, _ asleep: Bool) -> PetModel { let m = PetModel(); m.kind = kind; m.asleep = asleep; return m }
        let row = HStack(spacing: 14) {
            ForEach(PetKind.allCases) { k in
                VStack(spacing: 6) {
                    PetFace(cat: cat(k, false)).frame(width: 34, height: 26)
                    PetFace(cat: cat(k, true)).frame(width: 34, height: 26)
                }
            }
        }
        .padding(10)
        let view = VStack(spacing: 0) { row.background(Color.black); row.background(Color(white: 0.92)) }
        let r = ImageRenderer(content: view)
        r.scale = 8
        let rep = NSBitmapImageRep(cgImage: r.cgImage!)
        try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
    }
}
