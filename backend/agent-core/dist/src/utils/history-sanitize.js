"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PROGRESSIVE_DISCLOSURE_PLACEHOLDER = void 0;
exports.sanitizeMessageContentForAgent = sanitizeMessageContentForAgent;
exports.sanitizeHistoryForAgent = sanitizeHistoryForAgent;
exports.PROGRESSIVE_DISCLOSURE_PLACEHOLDER = "[Prior skill turn: parameter contract was requested; full disclosure text omitted.]";
const LARGE_DISCLOSURE_MIN_LEN = 800;
function normalizeMessageContentToString(content) {
    if (typeof content === "string")
        return content;
    if (content == null)
        return "";
    if (Array.isArray(content)) {
        return content
            .map((part) => {
            if (typeof part === "string")
                return part;
            if (part && typeof part === "object" && typeof part.text === "string") {
                return part.text;
            }
            return "";
        })
            .join("");
    }
    if (typeof content === "object") {
        return JSON.stringify(content);
    }
    return String(content);
}
function looksLikeRequireParametersObject(parsed) {
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed))
        return false;
    const st = parsed.status;
    return st === "REQUIRE_PARAMETERS";
}
function tryReplaceWholeJsonDisclosure(raw) {
    const t = raw.trim();
    if (!t.startsWith("{"))
        return null;
    try {
        const parsed = JSON.parse(t);
        if (looksLikeRequireParametersObject(parsed)) {
            return exports.PROGRESSIVE_DISCLOSURE_PLACEHOLDER;
        }
    }
    catch {
        return null;
    }
    return null;
}
function endOfBalancedJson(s, start) {
    if (s[start] !== "{")
        return null;
    let depth = 0;
    let inString = false;
    let escape = false;
    for (let i = start; i < s.length; i++) {
        const c = s[i];
        if (escape) {
            escape = false;
            continue;
        }
        if (inString) {
            if (c === "\\") {
                escape = true;
                continue;
            }
            if (c === '"')
                inString = false;
            continue;
        }
        if (c === '"') {
            inString = true;
            continue;
        }
        if (c === "{")
            depth++;
        else if (c === "}") {
            depth--;
            if (depth === 0)
                return i + 1;
        }
    }
    return null;
}
function replaceEmbeddedDisclosureJsonBlocks(s) {
    if (!s.includes("REQUIRE_PARAMETERS"))
        return s;
    const segments = [];
    let i = 0;
    while (i < s.length) {
        if (s[i] !== "{") {
            i += 1;
            continue;
        }
        const end = endOfBalancedJson(s, i);
        if (end == null) {
            i += 1;
            continue;
        }
        const slice = s.slice(i, end);
        if (slice.includes("REQUIRE_PARAMETERS")) {
            try {
                const parsed = JSON.parse(slice);
                if (looksLikeRequireParametersObject(parsed)) {
                    segments.push({ start: i, end });
                }
            }
            catch {
            }
        }
        i = end;
    }
    if (segments.length === 0)
        return s;
    let out = s;
    for (const seg of segments.sort((a, b) => b.start - a.start)) {
        out = out.slice(0, seg.start) + exports.PROGRESSIVE_DISCLOSURE_PLACEHOLDER + out.slice(seg.end);
    }
    return out;
}
function collapseHugeDisclosureHeuristic(s) {
    if (s.length < LARGE_DISCLOSURE_MIN_LEN)
        return s;
    const lower = s.toLowerCase();
    if (lower.includes("require_parameters")
        && (lower.includes("parametercontract") || lower.includes("parameter_contract"))) {
        return exports.PROGRESSIVE_DISCLOSURE_PLACEHOLDER;
    }
    return s;
}
function sanitizeMessageContentForAgent(rawContent) {
    let s = normalizeMessageContentToString(rawContent);
    if (!s)
        return s;
    const whole = tryReplaceWholeJsonDisclosure(s);
    if (whole != null)
        return whole;
    s = replaceEmbeddedDisclosureJsonBlocks(s);
    s = collapseHugeDisclosureHeuristic(s);
    return s;
}
function sanitizeHistoryForAgent(history) {
    if (!Array.isArray(history))
        return [];
    return history
        .filter((m) => {
        const role = m?.role;
        if (typeof role === 'string' && role.toLowerCase() === 'system') {
            return false;
        }
        return true;
    })
        .map((m) => {
        const next = { ...m };
        if ("content" in next) {
            next.content = sanitizeMessageContentForAgent(next.content);
        }
        return next;
    });
}
//# sourceMappingURL=history-sanitize.js.map