import { LoggerService } from '../../utils/logger.service';
export declare class OptimizeTextController {
    private readonly logger;
    constructor(logger: LoggerService);
    optimize(body: {
        fieldId: string;
        currentText: string;
        context?: string;
        llmApiBase?: string;
        llmModelName?: string;
        llmApiKey?: string;
    }): Promise<{
        optimizedText: string;
        explanation: string;
    } | {
        error: string;
        hint?: undefined;
    } | {
        error: string;
        hint: string;
    }>;
}
