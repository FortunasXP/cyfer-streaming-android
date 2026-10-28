import SwiftUI

/// The same badge set as Android's TechTag; logos come from the shared assets/logos files.
enum TechTag {
    case dolbyVision, hdr10Plus, hdr10, hlg, hdr, imaxEnhanced, imax, dolbyVisionFel
    case dolbyAtmos, dolbyTrueHD, dolbyDigitalPlus, dolbyDigital, dtsX, dtsHDMA, dtsHD, dts, aac, flac, opus, pcm

    var label: String {
        switch self {
        case .dolbyVision: return "Dolby Vision"
        case .hdr10Plus: return "HDR10+"
        case .hdr10: return "HDR10"
        case .hlg: return "HLG"
        case .hdr: return "HDR"
        case .imaxEnhanced: return "IMAX Enhanced"
        case .imax: return "IMAX"
        case .dolbyVisionFel: return "FEL"
        case .dolbyAtmos: return "Atmos"
        case .dolbyTrueHD: return "TrueHD"
        case .dolbyDigitalPlus: return "DD+"
        case .dolbyDigital: return "Dolby Digital"
        case .dtsX: return "DTS:X"
        case .dtsHDMA: return "DTS-HD MA"
        case .dtsHD: return "DTS-HD"
        case .dts: return "DTS"
        case .aac: return "AAC"
        case .flac: return "FLAC"
        case .opus: return "Opus"
        case .pcm: return "PCM"
        }
    }

    var asset: String? {
        switch self {
        case .dolbyVision: return "logo-dolby-vision"
        case .hdr10Plus: return "logo-hdr10-plus"
        case .hdr10: return "logo-hdr10"
        case .dolbyAtmos: return "logo-dolby-atmos"
        case .dolbyTrueHD: return "logo-dolby-truehd"
        case .dolbyDigitalPlus: return "logo-dolby-digital-plus"
        case .dolbyDigital: return "logo-dolby-digital"
        case .dtsX: return "logo-dts-x"
        case .dtsHDMA: return "logo-dts-hd-ma"
        case .dtsHD: return "logo-dts-hd"
        case .dts: return "logo-dts"
        case .aac: return "logo-aac"
        default: return nil
        }
    }
}

struct TechLogoBadge: View {
    let tag: TechTag
    var height: CGFloat = 14

    var body: some View {
        if let asset = tag.asset {
            Image(asset)
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(height: height)
                .foregroundColor(.white)
                .accessibilityLabel(tag.label)
        } else {
            Text(tag.label)
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(.white)
        }
    }
}

/// Port of Android's audioCodecTag: both engines report codecs in FFmpeg's words.
func audioCodecTag(_ codec: String?) -> TechTag? {
    guard let c = codec?.lowercased(), !c.isEmpty else { return nil }
    func word(_ w: String) -> Bool { c.range(of: "\\b\(w)\\b", options: .regularExpression) != nil }
    if c.contains("atmos") || c.contains("joc") || c.contains("ec+3") { return .dolbyAtmos }
    if c.contains("dts:x") || c.contains("dts.uhd") || word("dtsx") || word("dtsy") { return .dtsX }
    if c.contains("dts-hd ma") || word("dtsl") { return .dtsHDMA }
    if c.contains("dts express") || c.contains("profile=lbr") || word("dtse") { return .dts }
    if c.contains("dts-hd hra") || c.contains("dts.hd") || word("dtsh") { return .dtsHD }
    if c.contains("truehd") || c.contains("true-hd") || c.contains("mlp") { return .dolbyTrueHD }
    if c.contains("eac3") || c.contains("e-ac-3") || c.contains("ec-3") || c.contains("ddp") || c.contains("dd+") { return .dolbyDigitalPlus }
    if c.contains("ac3") || c.contains("ac-3") { return .dolbyDigital }
    if c.contains("dts") { return .dts }
    if c.contains("aac") || c.hasPrefix("mp4a") { return .aac }
    if c.contains("flac") { return .flac }
    if c.contains("opus") { return .opus }
    if c.contains("pcm") || c.contains("lpcm") { return .pcm }
    return nil
}

func channelLayout(_ channels: Int) -> String {
    switch channels {
    case ..<1: return ""
    case 1: return "Mono"
    case 2: return "Stereo"
    default: return "\(channels - 1).1"
    }
}

private let qualifierTitles: Set<String> = [
    "commentary", "director's commentary", "sdh", "forced", "signs", "songs", "signs & songs",
    "signs/songs", "full", "dialogue", "descriptive audio", "audio description", "dub", "original",
]

private func qualified(_ title: String, _ language: String) -> String {
    if language.isEmpty || !qualifierTitles.contains(title.lowercased()) { return title }
    if title.localizedCaseInsensitiveContains(language) { return title }
    return "\(language) \(title)"
}

func audioTrackTitle(id: Int, lang: String?, title: String?, channels: Int) -> String {
    let language = languageName(lang)
    if let t = title?.trimmingCharacters(in: .whitespaces), !t.isEmpty { return qualified(t, language) }
    let parts = [language, channelLayout(channels)].filter { !$0.isEmpty }
    return parts.isEmpty ? "Track \(id)" : parts.joined(separator: " ")
}

func subtitleTrackTitle(id: Int, lang: String?, title: String?, forced: Bool, hearingImpaired: Bool) -> String {
    let language = languageName(lang)
    if let t = title?.trimmingCharacters(in: .whitespaces), !t.isEmpty { return qualified(t, language) }
    let base = language.isEmpty ? "Track \(id)" : language
    if hearingImpaired { return "\(base) SDH" }
    if forced { return "\(base) (Forced)" }
    return base
}

/// Code forms Locale can't name: ISO 639-2/B (what muxers write) and OpenSubtitles' own codes.
private let languageAliases: [String: String] = [
    "en": "English", "eng": "English",
    "es": "Spanish", "spa": "Spanish", "spl": "Spanish (Latin America)",
    "pob": "Portuguese (Brazil)", "pom": "Portuguese (Portugal)",
    "zht": "Chinese (Traditional)", "zhc": "Chinese (Simplified)", "zhe": "Chinese (Bilingual)",
    "sr": "Serbian", "srp": "Serbian", "scc": "Serbian",
    "hr": "Croatian", "hrv": "Croatian", "scr": "Croatian",
    "bs": "Bosnian", "bos": "Bosnian",
    "ro": "Romanian", "ron": "Romanian", "rum": "Romanian",
    "bg": "Bulgarian", "bul": "Bulgarian",
    "sl": "Slovenian", "slv": "Slovenian",
    "sk": "Slovak", "slo": "Slovak", "slk": "Slovak",
    "hu": "Hungarian", "hun": "Hungarian",
    "uk": "Ukrainian", "ukr": "Ukrainian",
    "mk": "Macedonian", "mac": "Macedonian", "mkd": "Macedonian",
    "sq": "Albanian", "alb": "Albanian", "sqi": "Albanian",
    "fa": "Persian", "per": "Persian", "fas": "Persian",
    "is": "Icelandic", "ice": "Icelandic", "isl": "Icelandic",
    "ms": "Malay", "may": "Malay", "msa": "Malay",
    "hy": "Armenian", "arm": "Armenian", "hye": "Armenian",
    "ka": "Georgian", "geo": "Georgian", "kat": "Georgian",
    "my": "Burmese", "bur": "Burmese", "mya": "Burmese",
    "eu": "Basque", "baq": "Basque", "eus": "Basque",
    "fr": "French", "fre": "French", "fra": "French",
    "de": "German", "ger": "German", "deu": "German",
    "pt": "Portuguese", "por": "Portuguese",
    "zh": "Chinese", "chi": "Chinese", "zho": "Chinese",
    "nl": "Dutch", "dut": "Dutch", "nld": "Dutch",
    "cs": "Czech", "cze": "Czech", "ces": "Czech",
    "el": "Greek", "gre": "Greek", "ell": "Greek",
]

func languageName(_ code: String?) -> String {
    guard let c = code?.trimmingCharacters(in: .whitespaces).lowercased(), !c.isEmpty, c != "und" else { return "" }
    if let name = languageAliases[c] { return name }
    return Locale(identifier: "en").localizedString(forLanguageCode: c) ?? c.uppercased()
}
