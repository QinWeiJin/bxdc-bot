"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installDisableTiktokenNetwork = installDisableTiktokenNetwork;
let installed = false;
function installDisableTiktokenNetwork() {
    if (installed)
        return;
    installed = true;
    try {
        const mod = require("@langchain/core/utils/tiktoken");
        if (!mod || typeof mod.getEncoding !== "function") {
            console.warn("[tiktoken-network] Cannot find @langchain/core/utils/tiktoken");
            return;
        }
        mod.getEncoding = async function (_encoding) {
            throw new Error("tiktoken network access is disabled in offline mode");
        };
        mod.encodingForModel = async function (_model) {
            throw new Error("tiktoken network access is disabled in offline mode");
        };
        console.log("[tiktoken-network] ✅ Disabled tiktoken network fetch (offline mode)");
    }
    catch (e) {
        console.warn("[tiktoken-network] Failed to install:", e.message);
    }
}
//# sourceMappingURL=disable-tiktoken-network.js.map