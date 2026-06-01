"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const dotenv_1 = require("dotenv");
const core_1 = require("@nestjs/core");
const path_1 = require("path");
const app_module_1 = require("./app.module");
const envPath = (0, path_1.resolve)(process.cwd(), '.env');
console.log('[Bootstrap] Loading .env from:', envPath);
const result = (0, dotenv_1.config)({ path: envPath });
if (result.error) {
    console.log('[Bootstrap] No .env file found or error loading:', result.error.message);
}
else {
    console.log('[Bootstrap] .env loaded successfully');
    console.log('[Bootstrap] AGENT_PROMPTS_LANGUAGE =', process.env.AGENT_PROMPTS_LANGUAGE || '(not set)');
}
async function bootstrap() {
    const app = await core_1.NestFactory.create(app_module_1.AppModule);
    app.enableCors();
    const port = Number(process.env.PORT || 3000);
    const host = process.env.HOST || '0.0.0.0';
    await app.listen(port, host);
}
bootstrap();
//# sourceMappingURL=main.js.map