"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.OptimizeTextController = void 0;
const common_1 = require("@nestjs/common");
const optimize_text_service_1 = require("./optimize-text.service");
const llm_merge_1 = require("../../utils/llm-merge");
const logger_service_1 = require("../../utils/logger.service");
let OptimizeTextController = class OptimizeTextController {
    logger;
    constructor(logger) {
        this.logger = logger;
    }
    async optimize(body) {
        if (!body.fieldId || !body.currentText) {
            return { error: 'fieldId and currentText are required' };
        }
        const llm = (0, llm_merge_1.pickMergedLlm)(body);
        console.log('[optimize-text] llm config: hasApiKey=' + !!llm.apiKey + ' model=' + llm.modelName + ' baseUrl=' + (llm.baseUrl || 'default'));
        if (!llm.apiKey) {
            return { error: 'NO_API_KEY', hint: '请先在设置中配置 LLM API Key' };
        }
        const start = Date.now();
        const svc = new optimize_text_service_1.OptimizeTextService(llm.apiKey || '', llm.modelName, llm.baseUrl);
        const timeoutPromise = new Promise((resolve) => setTimeout(() => resolve({
            optimizedText: body.currentText,
            explanation: 'AI 优化超时（120s），请稍后重试。',
        }), 120000));
        const result = await Promise.race([
            svc.optimize(body.fieldId, body.currentText, body.context),
            timeoutPromise,
        ]);
        result.optimizedText = formatOptimizedText(body.fieldId, result.optimizedText);
        const duration = Date.now() - start;
        this.logger.logLlm('output', {
            feature: 'text-optimize',
            fieldId: body.fieldId,
            duration: `${duration}ms`,
        });
        return result;
    }
};
exports.OptimizeTextController = OptimizeTextController;
__decorate([
    (0, common_1.Post)(),
    __param(0, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object]),
    __metadata("design:returntype", Promise)
], OptimizeTextController.prototype, "optimize", null);
exports.OptimizeTextController = OptimizeTextController = __decorate([
    (0, common_1.Controller)('features/optimize-text'),
    __metadata("design:paramtypes", [logger_service_1.LoggerService])
], OptimizeTextController);
const JSON_FIELD_IDS = new Set([
    'api_parameter_contract',
    'api_async_poll',
    'api_headers',
    'api_query',
    'api_body',
]);
function formatOptimizedText(fieldId, text) {
    if (typeof text !== 'string') {
        if (text && typeof text === 'object') {
            try {
                return JSON.stringify(text, null, 2);
            }
            catch {
                return String(text);
            }
        }
        return text;
    }
    if (!JSON_FIELD_IDS.has(fieldId))
        return text;
    const trimmed = text.trim();
    if (!trimmed)
        return text;
    try {
        return JSON.stringify(JSON.parse(trimmed), null, 2);
    }
    catch {
        return text;
    }
}
//# sourceMappingURL=optimize-text.controller.js.map