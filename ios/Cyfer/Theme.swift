import SwiftUI

extension Color {
    static let cyferGlass = Color.white.opacity(0.14)
    static let cyferPanel = Color(red: 0x12 / 255, green: 0x12 / 255, blue: 0x12 / 255).opacity(0xF0 / 255)
    static let cyferSurface = Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255)
    static let cyferCyan = Color(red: 0x26 / 255, green: 0xC6 / 255, blue: 0xDA / 255)
    static let cyferRed = Color(red: 0xEF / 255, green: 0x53 / 255, blue: 0x50 / 255)
    static let cyferAmber = Color(red: 0xFF / 255, green: 0xA0 / 255, blue: 0x00 / 255)
}

func formatTime(_ seconds: Double) -> String {
    let total = max(0, Int(seconds.isFinite ? seconds : 0))
    let h = total / 3600, m = (total % 3600) / 60, s = total % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
