import { OnModuleInit } from '@nestjs/common';
import { LoggerService } from '../utils/logger.service';
export declare class MemoryService implements OnModuleInit {
    private readonly logger;
    private mem0Url;
    private readonly mem0Enabled;
    constructor(logger: LoggerService);
    onModuleInit(): void;
    private readMem0EnabledFlag;
    searchMemories(query: string, userId?: string, limit?: number): Promise<string[]>;
    getAllMemories(userId?: string, limit?: number): Promise<string[]>;
    processTurn(options: {
        sessionId: string;
        userId?: string;
        userText: string;
        assistantText: string;
    }): Promise<void>;
    addMemory(userId: string, text: string, role?: 'user' | 'assistant' | 'system'): Promise<void>;
    fetchUserProfile(userId: string): Promise<string>;
}
