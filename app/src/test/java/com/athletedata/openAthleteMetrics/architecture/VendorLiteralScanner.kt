package com.athletedata.openAthleteMetrics.architecture

/**
 * Describes one vendor's reserved literals for [VendorLiteralScanner]. Add an entry per
 * driver here (in the test that owns the registry) when that driver needs an empirically
 * scoped, driverId-guarded exception in generic engine code — this is the single place a
 * new vendor token gets registered so the guard covers it automatically.
 */
data class VendorTokenRegistry(
    val vendorName: String,
    val forbiddenOpcodes: List<String>,
    val forbiddenNameSubstrings: List<String>,
    val guardConstantName: String,
    val guardConstantValue: String,
)

data class Violation(
    val filePath: String,
    val lineNumber: Int,
    val lineText: String,
    val token: String,
    val reason: String,
) {
    fun message(registry: VendorTokenRegistry): String =
        "$filePath:$lineNumber: forbidden vendor literal \"$token\" ($reason) for vendor " +
            "\"${registry.vendorName}\". An opcode literal is allowed only inside a scope directly " +
            "guarded by `driverId.startsWith(${registry.guardConstantName})` or " +
            "`driverId == ${registry.guardConstantName}` referencing that declared constant — not a " +
            "re-typed string literal. A vendor name substring is allowed only as the value of that " +
            "constant's own declaration (`... ${registry.guardConstantName} = \"${registry.guardConstantValue}\"`) " +
            "— never elsewhere, including comments. Offending line: \"${lineText.trim()}\""
}

/**
 * Scans Kotlin source text for vendor-specific opcode/name literals that leak into generic
 * engine code unconditionally. An opcode is exempt only while every occurrence sits inside a
 * lexical block directly guarded by `driverId.startsWith(NAMED_CONSTANT)` or
 * `driverId == NAMED_CONSTANT` (never an inline re-typed string). A vendor name substring is
 * exempt only as the value of that one named constant's own declaration — nowhere else,
 * including comments.
 *
 * This is a bespoke line/brace scanner, not a real Kotlin parser: it strips `//` line
 * comments before counting braces (so `{`/`}` inside comments never confuse block matching)
 * but does not special-case braces inside string literals — acceptable because the
 * control-flow code this rule polices doesn't hide braces in strings.
 */
object VendorLiteralScanner {

    fun scan(filePath: String, source: String, registry: VendorTokenRegistry): List<Violation> {
        val lines = source.lines()
        val guardedRanges = findGuardedRanges(lines, registry)
        return checkOpcodes(lines, guardedRanges, registry, filePath) +
            checkNameSubstrings(lines, registry, filePath)
    }

    private fun stripLineComment(line: String): String {
        val idx = line.indexOf("//")
        return if (idx >= 0) line.substring(0, idx) else line
    }

    /**
     * Finds every `{ ... }` block opened by a line matching a valid driverId guard for
     * [registry], returning the (guard-line, block-close-line) range for each. A range only
     * ever starts at the guard's own line and extends forward to its block's closing brace,
     * so a guard appearing after the point of use never exempts that use — and multiple
     * independent guarded blocks in one file are all tracked.
     */
    private fun findGuardedRanges(lines: List<String>, registry: VendorTokenRegistry): List<IntRange> {
        val guardRegexes = listOf(
            Regex("""driverId\.startsWith\(\s*${Regex.escape(registry.guardConstantName)}\s*\)"""),
            Regex("""driverId\s*==\s*${Regex.escape(registry.guardConstantName)}\b"""),
        )
        val ranges = mutableListOf<IntRange>()
        for (guardLine in lines.indices) {
            val codeOnly = stripLineComment(lines[guardLine])
            if (guardRegexes.none { it.containsMatchIn(codeOnly) }) continue

            var openLine = -1
            var openCol = -1
            search@ for (j in guardLine until lines.size) {
                val text = stripLineComment(lines[j])
                for (col in text.indices) {
                    if (text[col] == '{') {
                        openLine = j
                        openCol = col
                        break@search
                    }
                }
            }
            if (openLine == -1) continue // guard with no block body; nothing to scope

            var depth = 1
            var j = openLine
            var col = openCol + 1
            var endLine = -1
            while (j < lines.size && endLine == -1) {
                val text = stripLineComment(lines[j])
                while (col < text.length) {
                    when (text[col]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                endLine = j
                                break
                            }
                        }
                    }
                    col++
                }
                if (endLine == -1) {
                    j++
                    col = 0
                }
            }
            if (endLine == -1) continue // unbalanced braces; skip defensively

            ranges.add(guardLine..endLine)
        }
        return ranges
    }

    private fun checkOpcodes(
        lines: List<String>,
        guardedRanges: List<IntRange>,
        registry: VendorTokenRegistry,
        filePath: String,
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        for (opcode in registry.forbiddenOpcodes) {
            val tokenRegex = Regex("""(?<![0-9A-Fa-f])${Regex.escape(opcode)}(?![0-9A-Fa-f])""")
            for (i in lines.indices) {
                if (!tokenRegex.containsMatchIn(lines[i])) continue
                val guarded = guardedRanges.any { i in it }
                if (!guarded) {
                    violations.add(
                        Violation(
                            filePath = filePath,
                            lineNumber = i + 1,
                            lineText = lines[i],
                            token = opcode,
                            reason = "OPCODE_OUTSIDE_GUARD_SCOPE",
                        ),
                    )
                }
            }
        }
        return violations
    }

    /**
     * A vendor name substring is exempt only on the one line that declares the shared
     * driverId-prefix constant (its identifier AND its quoted value both live there), and
     * only as a reference to that same constant identifier elsewhere (e.g.
     * `driverId.startsWith(HUME_BAND_DRIVER_ID_PREFIX)` — the identifier is stripped before
     * matching, so referencing the shared constant is never itself a violation). Any other
     * occurrence — a re-typed string literal, a new identifier embedding the vendor name, or
     * prose in a comment — is a violation, even inside an otherwise-guarded block.
     */
    private fun checkNameSubstrings(
        lines: List<String>,
        registry: VendorTokenRegistry,
        filePath: String,
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        val identifierRegex = Regex("""\b${Regex.escape(registry.guardConstantName)}\b""")
        val declarationRegex = Regex(
            """const\s+val\s+${Regex.escape(registry.guardConstantName)}\s*(?::\s*String)?\s*=\s*"${Regex.escape(registry.guardConstantValue)}"""",
        )
        for (i in lines.indices) {
            val line = lines[i]
            if (declarationRegex.containsMatchIn(line)) continue // the one sanctioned line

            val withoutSharedConstantRefs = identifierRegex.replace(line, "")
            for (substring in registry.forbiddenNameSubstrings) {
                if (!withoutSharedConstantRefs.contains(substring, ignoreCase = true)) continue
                violations.add(
                    Violation(
                        filePath = filePath,
                        lineNumber = i + 1,
                        lineText = line,
                        token = substring,
                        reason = "NAME_SUBSTRING_OUTSIDE_DECLARATION",
                    ),
                )
            }
        }
        return violations
    }
}
