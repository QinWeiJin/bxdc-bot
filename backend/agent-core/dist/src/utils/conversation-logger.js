"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConversationLogger = void 0;
const axios_1 = __importDefault(require("axios"));
class ConversationLogger {
    gatewayUrl;
    constructor() {
        this.gatewayUrl = process.env.JAVA_GATEWAY_URL || 'http://localhost:18080';
    }
    async logConversation(log) {
        try {
            const url = `${this.gatewayUrl}/api/internal/conversation-logs`;
            await axios_1.default.post(url, log, {
                headers: {
                    'Content-Type': 'application/json',
                },
                timeout: 10000,
            });
            console.log(`[ConversationLogger] Logged conversation for session ${log.sessionId}`);
        }
        catch (error) {
            console.error(`[ConversationLogger] Failed to log conversation: ${error}`);
        }
    }
    async logToolCall(log) {
        try {
            const url = `${this.gatewayUrl}/api/internal/tool-call-logs`;
            await axios_1.default.post(url, log, {
                headers: {
                    'Content-Type': 'application/json',
                },
                timeout: 10000,
            });
            console.log(`[ConversationLogger] Logged tool call: ${log.toolName} for session ${log.sessionId}`);
        }
        catch (error) {
            console.error(`[ConversationLogger] Failed to log tool call: ${error}`);
        }
    }
}
exports.ConversationLogger = ConversationLogger;
//# sourceMappingURL=conversation-logger.js.map