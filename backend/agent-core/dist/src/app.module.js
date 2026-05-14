"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.AppModule = void 0;
const common_1 = require("@nestjs/common");
const config_1 = require("@nestjs/config");
const agent_controller_1 = require("./controller/agent.controller");
const health_controller_1 = require("./controller/health.controller");
const memory_controller_1 = require("./controller/memory.controller");
const user_controller_1 = require("./controller/user.controller");
const avatar_controller_1 = require("./features/avatar/avatar.controller");
const optimize_text_controller_1 = require("./features/optimize-text/optimize-text.controller");
const skill_proxy_controller_1 = require("./features/skills/skill-proxy.controller");
const memory_service_1 = require("./mem/memory.service");
const skill_manager_1 = require("./skills/skill.manager");
const logger_service_1 = require("./utils/logger.service");
let AppModule = class AppModule {
};
exports.AppModule = AppModule;
exports.AppModule = AppModule = __decorate([
    (0, common_1.Module)({
        imports: [config_1.ConfigModule.forRoot()],
        controllers: [agent_controller_1.AgentController, health_controller_1.HealthController, memory_controller_1.MemoryController, user_controller_1.UserController, avatar_controller_1.AvatarController, optimize_text_controller_1.OptimizeTextController, skill_proxy_controller_1.SkillProxyController],
        providers: [memory_service_1.MemoryService, skill_manager_1.SkillManager, logger_service_1.LoggerService],
    })
], AppModule);
//# sourceMappingURL=app.module.js.map