export declare class HealthController {
    private readonly version;
    constructor();
    private loadVersion;
    getHealth(): {
        status: string;
        service: string;
        version: string;
        timestamp: string;
        uptimeSeconds: number;
    };
}
