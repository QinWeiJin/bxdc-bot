import { MemoryService } from '../mem/memory.service';
export declare class MemoryController {
    private readonly memoryService;
    constructor(memoryService: MemoryService);
    addMemory(body: {
        userId: string;
        text: string;
        role?: 'user' | 'assistant' | 'system';
    }): Promise<void>;
    getProfile(userId: string): Promise<{
        userId: string;
        details: string;
        success: boolean;
    }>;
}
