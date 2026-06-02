"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.pickMergedLlm = pickMergedLlm;
function pickMergedLlm(overrides) {
    const pick = (u, e) => u != null && String(u).trim() !== '' ? String(u).trim() : e;
    const apiKey = pick(overrides?.llmApiKey, process.env.OPENAI_API_KEY);
    const modelName = pick(overrides?.llmModelName, process.env.OPENAI_MODEL_NAME) || 'gpt-4';
    const baseUrl = pick(overrides?.llmApiBase, process.env.OPENAI_API_BASE);
    return { apiKey, modelName, baseUrl };
}
//# sourceMappingURL=llm-merge.js.map