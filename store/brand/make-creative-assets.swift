// Renders App Store "creative assets" (product-page header / search-results artwork) for
// Jukiza-Reminder. Run from the repo root:
//   export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
//   xcrun swift store/brand/make-creative-assets.swift
// Output: store/creative-assets/*.png — opaque PNG (no alpha), sRGB.
//
// Sizes (App Store Connect Asset Library, iOS/iPadOS 27+):
//   universal 5244×2950 (16:9; serves header + search), search 3840×2560 and 1920×1280 (3:2),
//   header 3840×1646 (21:9). Key content stays inside the centered safe zone Apple's templates
//   mark; everything outside is decorative and may be cropped per device.
import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

struct Spec {
    let file: String
    let w: Int, h: Int
    let safeW: CGFloat, safeH: CGFloat
    let safeAbove: CGFloat   // safe zone centre sits this many px above the canvas centre
    let scale: CGFloat       // 1.0 = native; 0.5 renders the 3840 layout at half size
}

let specs: [Spec] = [
    Spec(file: "universal-5244x2950.png", w: 5244, h: 2950, safeW: 1921, safeH: 962,  safeAbove: 334, scale: 1),
    Spec(file: "search-3840x2560.png",    w: 3840, h: 2560, safeW: 2168, safeH: 1030, safeAbove: 0,   scale: 1),
    Spec(file: "search-1920x1280.png",    w: 1920, h: 1280, safeW: 2168, safeH: 1030, safeAbove: 0,   scale: 0.5),
    Spec(file: "header-3840x1646.png",    w: 3840, h: 1646, safeW: 1646, safeH: 661,  safeAbove: 0,   scale: 1),
]

let root = FileManager.default.currentDirectoryPath
let shotURL = URL(fileURLWithPath: "\(root)/store/screenshots/iphone-18-pro-max/light/1-today.png")
let iconURL = URL(fileURLWithPath: "\(root)/store/brand/icon-1024.png")
let outDir = URL(fileURLWithPath: "\(root)/store/creative-assets")

func loadCG(_ url: URL) -> CGImage {
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
          let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { fatalError("cannot read \(url.path)") }
    return img
}
let shot = loadCG(shotURL)
let icon = loadCG(iconURL)

func rgb(_ hex: UInt32) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
}
let cream = rgb(0xFFF8F5), coral = rgb(0xF2654E), coralLight = rgb(0xFFDBD1), tealLight = rgb(0xB9EFE8)
let sunnyLight = rgb(0xFFE6B5), ink = rgb(0x2B1A16), inkSoft = rgb(0x6B4F47)

func roundedFont(_ size: CGFloat, _ weight: NSFont.Weight) -> NSFont {
    let base = NSFont.systemFont(ofSize: size, weight: weight)
    if let d = base.fontDescriptor.withDesign(.rounded), let f = NSFont(descriptor: d, size: size) { return f }
    return base
}

func render(_ s: Spec) {
    let cs = CGColorSpace(name: CGColorSpace.sRGB)!
    guard let ctx = CGContext(data: nil, width: s.w, height: s.h, bitsPerComponent: 8, bytesPerRow: 0,
                              space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { fatalError("ctx") }
    ctx.scaleBy(x: s.scale, y: s.scale)
    let W = CGFloat(s.w) / s.scale, H = CGFloat(s.h) / s.scale
    let nsctx = NSGraphicsContext(cgContext: ctx, flipped: false)
    NSGraphicsContext.current = nsctx

    // Background: cream with big soft colour fields, like the app's greeting header.
    ctx.setFillColor(cream.cgColor); ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
    func blob(_ c: NSColor, _ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ a: CGFloat) {
        ctx.setFillColor(c.withAlphaComponent(a).cgColor)
        ctx.fillEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
    }
    blob(coralLight, W * 0.12, H * 0.08, H * 0.62, 0.9)
    blob(tealLight,  W * 0.88, H * 0.95, H * 0.58, 0.75)
    blob(sunnyLight, W * 0.60, H * -0.10, H * 0.42, 0.8)

    // Safe zone geometry.
    let cx = W / 2, cy = H / 2 + s.safeAbove
    let phoneH = min(s.safeH * 1.6, H * 0.64)
    let phoneW = phoneH * CGFloat(shot.width) / CGFloat(shot.height)
    let bezel = phoneW * 0.028
    let gap = s.safeW * 0.06
    let phoneX = cx + gap            // phone on the right half of the safe zone
    let phoneRect = CGRect(x: phoneX, y: cy - phoneH / 2, width: phoneW, height: phoneH)

    // Phone: shadow, dark body, clipped screenshot.
    let body = phoneRect.insetBy(dx: -bezel, dy: -bezel)
    let bodyPath = CGPath(roundedRect: body, cornerWidth: phoneW * 0.17, cornerHeight: phoneW * 0.17, transform: nil)
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -phoneH * 0.02), blur: phoneH * 0.06,
                  color: NSColor.black.withAlphaComponent(0.22).cgColor)
    ctx.setFillColor(ink.cgColor); ctx.addPath(bodyPath); ctx.fillPath()
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addPath(CGPath(roundedRect: phoneRect, cornerWidth: phoneW * 0.14, cornerHeight: phoneW * 0.14, transform: nil))
    ctx.clip()
    ctx.draw(shot, in: phoneRect)
    ctx.restoreGState()

    // Text block: icon, name, tagline, right-aligned towards the phone, inside the safe zone.
    let blockRight = cx - gap
    let blockLeft = cx - s.safeW / 2
    let blockW = blockRight - blockLeft
    let iconSize = phoneH * 0.21
    var nameSize = phoneH * 0.075
    let name = "Jukiza-Reminder"
    func attrs(_ f: NSFont, _ c: NSColor) -> [NSAttributedString.Key: Any] { [.font: f, .foregroundColor: c] }
    var nameAttr = NSAttributedString(string: name, attributes: attrs(roundedFont(nameSize, .heavy), ink))
    while nameAttr.size().width > blockW && nameSize > 20 {
        nameSize *= 0.96
        nameAttr = NSAttributedString(string: name, attributes: attrs(roundedFont(nameSize, .heavy), ink))
    }
    let tagAttr = NSAttributedString(string: "Rings the minute it’s due.",
                                     attributes: attrs(roundedFont(nameSize * 0.56, .semibold), inkSoft))
    let lineGap = nameSize * 0.35
    let totalH = iconSize + lineGap * 1.4 + nameAttr.size().height + lineGap * 0.4 + tagAttr.size().height
    var y = cy + totalH / 2

    // Icon with rounded mask.
    let iconRect = CGRect(x: blockRight - iconSize, y: y - iconSize, width: iconSize, height: iconSize)
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -iconSize * 0.04), blur: iconSize * 0.12,
                  color: NSColor.black.withAlphaComponent(0.18).cgColor)
    ctx.setFillColor(coral.cgColor)
    ctx.addPath(CGPath(roundedRect: iconRect, cornerWidth: iconSize * 0.225, cornerHeight: iconSize * 0.225, transform: nil))
    ctx.fillPath()
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addPath(CGPath(roundedRect: iconRect, cornerWidth: iconSize * 0.225, cornerHeight: iconSize * 0.225, transform: nil))
    ctx.clip(); ctx.draw(icon, in: iconRect); ctx.restoreGState()
    y -= iconSize + lineGap * 1.4

    let nameW = nameAttr.size().width
    nameAttr.draw(at: NSPoint(x: blockRight - nameW, y: y - nameAttr.size().height))
    y -= nameAttr.size().height + lineGap * 0.4
    let tagW = tagAttr.size().width
    tagAttr.draw(at: NSPoint(x: blockRight - tagW, y: y - tagAttr.size().height))

    NSGraphicsContext.current = nil
    guard let out = ctx.makeImage() else { fatalError("image") }
    let url = outDir.appendingPathComponent(s.file)
    guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else { fatalError("dest") }
    CGImageDestinationAddImage(dest, out, nil)
    precondition(CGImageDestinationFinalize(dest), "write failed")
    print("wrote \(s.file) \(s.w)x\(s.h)")
}

try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
specs.forEach(render)
