import MarkdownUI
import SwiftUI

/// Colours code blocks: comments, strings, keywords, numbers, types and calls. A small
/// tokenizer, not a parser, tuned for what Claude writes most: TypeScript/JavaScript,
/// Swift, Kotlin, Python, Go, Rust, shell, JSON, CSS and SQL.
struct BenchHighlighter: CodeSyntaxHighlighter {
    let dark: Bool
    let base: Color

    private var keyword: Color { dark ? Color(red: 0.99, green: 0.47, blue: 0.66) : Color(red: 0.68, green: 0.13, blue: 0.47) }
    private var string: Color { dark ? Color(red: 0.62, green: 0.85, blue: 0.47) : Color(red: 0.12, green: 0.50, blue: 0.20) }
    private var number: Color { dark ? Color(red: 0.98, green: 0.72, blue: 0.42) : Color(red: 0.70, green: 0.38, blue: 0.0) }
    private var comment: Color { dark ? Color(red: 0.50, green: 0.53, blue: 0.58) : Color(red: 0.45, green: 0.47, blue: 0.51) }
    private var type: Color { dark ? Color(red: 0.45, green: 0.80, blue: 0.95) : Color(red: 0.05, green: 0.42, blue: 0.62) }
    private var call: Color { dark ? Color(red: 0.55, green: 0.70, blue: 1.0) : Color(red: 0.18, green: 0.36, blue: 0.80) }

    func highlightCode(_ code: String, language: String?) -> Text {
        Text(attributed(code, language: language?.lowercased() ?? ""))
    }

    private static let keywords: Set<String> = [
        // JS / TS
        "function", "return", "const", "let", "var", "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
        "new", "class", "extends", "implements", "interface", "type", "enum", "import", "export", "from", "default", "as",
        "async", "await", "try", "catch", "finally", "throw", "typeof", "instanceof", "in", "of", "this", "super", "null",
        "undefined", "true", "false", "void", "public", "private", "protected", "readonly", "static", "abstract", "yield",
        // Swift / Kotlin
        "func", "struct", "protocol", "extension", "guard", "defer", "self", "Self", "nil", "init", "deinit", "where",
        "some", "any", "inout", "throws", "rethrows", "mutating", "override", "final", "open", "fileprivate", "internal",
        "fun", "val", "object", "data", "sealed", "when", "is", "companion", "suspend", "lateinit", "by",
        // Python
        "def", "lambda", "pass", "None", "True", "False", "and", "or", "not", "with", "elif", "except", "raise", "global",
        "nonlocal", "assert", "del",
        // Go / Rust
        "package", "go", "chan", "select", "range", "map", "fn", "mut", "impl", "trait", "pub", "use", "mod", "crate",
        "match", "loop", "move", "ref", "unsafe", "dyn",
        // SQL / shell
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "JOIN", "ON",
        "AND", "OR", "NOT", "ORDER", "BY", "GROUP", "LIMIT", "then", "fi", "done", "esac", "echo",
    ]

    func attributed(_ code: String, language: String) -> AttributedString {
        var out = AttributedString(code)
        out.foregroundColor = base
        let ns = code as NSString
        var taken = IndexSet()

        func paint(_ pattern: String, _ color: Color, group: Int = 0, options: NSRegularExpression.Options = []) {
            guard let re = try? NSRegularExpression(pattern: pattern, options: options) else { return }
            for m in re.matches(in: code, range: NSRange(location: 0, length: ns.length)) {
                let r = m.range(at: group)
                guard r.location != NSNotFound, r.length > 0, !taken.intersects(integersIn: r.location..<(r.location + r.length)) else { continue }
                taken.insert(integersIn: r.location..<(r.location + r.length))
                if let range = Range(r, in: code), let a = Range(range, in: out) { out[a].foregroundColor = color }
            }
        }

        // Markdown is prose: colour its structure, never its words as keywords.
        if ["md", "markdown", "mdx"].contains(language) {
            paint(#"(?m)^(```|~~~)[\s\S]*?^\1[^\n]*$"#, string)
            paint(#"`[^`\n]+`"#, string)
            paint(#"(?m)^#{1,6} [^\n]*$"#, keyword)
            paint(#"(?m)^>[^\n]*$"#, comment)
            paint(#"(?m)^\s*([-*+]|\d+[.)])(?= )"#, number)
            paint(#"\[[^\]\n]*\]\([^)\n]*\)"#, call)
            paint(#"<!--[\s\S]*?-->"#, comment)
            return out
        }

        let hashComments = ["python", "py", "sh", "bash", "zsh", "shell", "ruby", "rb", "yaml", "yml", "toml", "dockerfile", "makefile"].contains(language)
        // Comments and strings first, so keywords inside them stay their colour.
        if hashComments { paint(#"#[^\n]*"#, comment) }
        if language == "sql" { paint(#"--[^\n]*"#, comment) }
        if !hashComments { paint(#"//[^\n]*"#, comment); paint(#"/\*[\s\S]*?\*/"#, comment) }
        paint(#""""[\s\S]*?""""#, string)
        paint(#"`(?:\\.|[^`\\])*`"#, string)
        paint(#""(?:\\.|[^"\\\n])*""#, string)
        paint(#"'(?:\\.|[^'\\\n])*'"#, string)
        paint(#"\b(?:0x[0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?(?:e[+-]?\d+)?)\b"#, number)
        if ["json", "jsonc"].contains(language) { return out }
        if ["css", "scss"].contains(language) {
            paint(#"([a-z-]+)\s*:"#, keyword, group: 1)
            return out
        }
        // Keywords, then Capitalised names as types, then name( as a call.
        guard let word = try? NSRegularExpression(pattern: #"\b[A-Za-z_][A-Za-z0-9_]*\b"#) else { return out }
        for m in word.matches(in: code, range: NSRange(location: 0, length: ns.length)) {
            let r = m.range
            guard !taken.intersects(integersIn: r.location..<(r.location + r.length)) else { continue }
            let w = ns.substring(with: r)
            let isSQL = language == "sql"
            if Self.keywords.contains(w) || (isSQL && Self.keywords.contains(w.uppercased())) {
                taken.insert(integersIn: r.location..<(r.location + r.length))
                if let range = Range(r, in: code), let a = Range(range, in: out) { out[a].foregroundColor = keyword }
            }
        }
        paint(#"\b[A-Z][A-Za-z0-9_]*\b"#, type)
        paint(#"\b([a-z_][A-Za-z0-9_]*)\s*(?=\()"#, call, group: 1)
        return out
    }
}
