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
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.MemoryService = void 0;
const common_1 = require("@nestjs/common");
const axios_1 = __importDefault(require("axios"));
const logger_service_1 = require("../utils/logger.service");
let MemoryService = class MemoryService {
    logger;
    mem0Url;
    mem0Enabled;
    constructor(logger) {
        this.logger = logger;
        this.mem0Enabled = this.readMem0EnabledFlag();
    }
    onModuleInit() {
        this.mem0Url = process.env.MEM0_URL || 'http://39.104.81.41:8001';
        if (!this.mem0Enabled) {
            console.log('[MemoryService] mem0 is disabled (MEM0_ENABLED=false); search/store skipped');
            return;
        }
        console.log(`[MemoryService] Initialized with mem0 service at: ${this.mem0Url}`);
    }
    readMem0EnabledFlag() {
        const v = process.env.MEM0_ENABLED?.trim().toLowerCase();
        if (v === 'false' || v === '0' || v === 'off' || v === 'no' || v === 'disabled') {
            return false;
        }
        return true;
    }
    async searchMemories(query, userId, limit = 10) {
        if (!this.mem0Enabled) {
            return [];
        }
        if (!userId) {
            console.warn('[MemoryService] searchMemories skipped: userId is missing');
            return [];
        }
        try {
            const requestData = {
                sentence: query,
                userid: userId,
                topk: limit
            };
            const response = await axios_1.default.post(`${this.mem0Url}/msearch`, requestData);
            if (response.data && response.data.code === 200) {
                const rawResults = response.data.results ||
                    response.data.data ||
                    response.data.memories ||
                    response.data.details ||
                    [];
                let results = [];
                if (Array.isArray(rawResults)) {
                    results = rawResults.map(item => typeof item === 'string' ? item : JSON.stringify(item));
                }
                else if (typeof rawResults === 'string' && rawResults.length > 0) {
                    results = rawResults.split(/[\n,，]/).filter(s => s.trim().length > 0);
                }
                this.logger.logMemory('retrieve', {
                    request: requestData,
                    response: response.data,
                    parsedResults: results
                });
                console.log(`[MemoryService] Found ${results.length} memories for query: "${query}"`);
                return results;
            }
            this.logger.logMemory('retrieve', {
                request: requestData,
                error: 'Invalid response code',
                response: response.data
            });
            return [];
        }
        catch (e) {
            console.error('[MemoryService] msearch error:', e.message);
            this.logger.logMemory('retrieve', {
                query,
                userId,
                error: e.message
            });
            return [];
        }
    }
    async getAllMemories(userId, limit = 50) {
        return this.searchMemories('', userId, limit);
    }
    async processTurn(options) {
        if (!this.mem0Enabled) {
            return;
        }
        if (!options.userId) {
            console.warn('[MemoryService] processTurn skipped: userId is missing');
            return;
        }
        try {
            const requestData = {
                sentencein: options.userText,
                sentenceout: options.assistantText,
                userid: options.userId
            };
            const response = await axios_1.default.post(`${this.mem0Url}/madd`, requestData);
            if (response.data && response.data.code === 200) {
                console.log('[MemoryService] Memory stored in mem0:', response.data.message);
                this.logger.logMemory('store', {
                    request: requestData,
                    response: response.data
                });
            }
            else {
                console.warn('[MemoryService] mem0 madd failed:', response.data);
                this.logger.logMemory('store', {
                    request: requestData,
                    error: 'Failed to store',
                    response: response.data
                });
            }
        }
        catch (e) {
            console.error('[MemoryService] madd error:', e.message);
            this.logger.logMemory('store', {
                options,
                error: e.message
            });
        }
    }
    async addMemory(userId, text, role = 'system') {
        return this.processTurn({
            sessionId: 'explicit-add',
            userId,
            userText: text,
            assistantText: '好的，我已经记住了。'
        });
    }
    async fetchUserProfile(userId) {
        const enabled = process.env.MEM0_ENABLED?.trim().toLowerCase();
        if (enabled === 'false' || enabled === '0' || enabled === 'off' || enabled === 'no') {
            return '';
        }
        if (!userId) {
            return '';
        }
        const url = process.env.MEM0_URL;
        if (!url) {
            return '';
        }
        try {
            const response = await axios_1.default.post(url, {
                sentence: '梦境记忆',
                userid: userId,
                topk: 1
            }, { timeout: 5000 });
            if (response.data?.code === 200 && response.data?.details) {
                return response.data.details;
            }
            return '';
        }
        catch (e) {
            console.error('[MemoryService] fetchUserProfile error:', e.message);
            return '';
        }
    }
};
exports.MemoryService = MemoryService;
exports.MemoryService = MemoryService = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [logger_service_1.LoggerService])
], MemoryService);
//# sourceMappingURL=memory.service.js.map