// SPDX-License-Identifier: AGPL-3.0-only
// Reproduce the original iOS port vector icon: swift Config/DrawIcon.swift
import AppKit

guard let context = CGContext(data: nil, width: 1024, height: 1024,
                              bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
    fatalError("Icon bitmap creation failed")
}
let green = CGColor(red: 0.09, green: 0.49, blue: 0.39, alpha: 1)
context.setFillColor(green)
context.fill(CGRect(x: 0, y: 0, width: 1024, height: 1024))
context.move(to: CGPoint(x: 295, y: 325))
context.addCurve(to: CGPoint(x: 759, y: 761), control1: CGPoint(x: 235, y: 645), control2: CGPoint(x: 450, y: 808))
context.addCurve(to: CGPoint(x: 295, y: 325), control1: CGPoint(x: 832, y: 420), control2: CGPoint(x: 555, y: 264))
context.closePath()
context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
context.fillPath()
context.move(to: CGPoint(x: 271, y: 263))
context.addCurve(to: CGPoint(x: 683, y: 666), control1: CGPoint(x: 355, y: 445), control2: CGPoint(x: 523, y: 572))
context.setLineWidth(28)
context.setLineCap(.round)
context.setStrokeColor(green)
context.strokePath()
guard let image = context.makeImage() else { fatalError("Icon rendering failed") }
let bitmap = NSBitmapImageRep(cgImage: image)
guard let png = bitmap.representation(using: .png, properties: [:]) else { fatalError("Icon rendering failed") }
try png.write(to: URL(fileURLWithPath: "App/Assets.xcassets/AppIcon.appiconset/AppIcon.png"))
