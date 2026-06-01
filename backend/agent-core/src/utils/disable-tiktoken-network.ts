/**
 * 禁用 tiktoken 网络访问补丁
 *
 * 背景：@langchain/core/dist/utils/tiktoken.cjs 默认从 https://tiktoken.pages.dev/js/${encoding}.json
 * 下载 BPE 编码文件。内网环境无法访问外网，会导致每次 LLM 调用都卡住等待 DNS 超时。
 *
 * 本工具：拦截 tiktoken 模块的 fetch 请求，立即抛错（避免长时间卡顿）。
 * 内网场景下 LLM token 计数会有偏差，但不影响正常对话功能。
 *
 * 使用方式：在 main.ts 启动早期调用 installDisableTiktokenNetwork()
 */

let installed = false;

export function installDisableTiktokenNetwork(): void {
  if (installed) return;
  installed = true;

  try {
    const mod = require("@langchain/core/utils/tiktoken");
    if (!mod || typeof mod.getEncoding !== "function") {
      console.warn("[tiktoken-network] Cannot find @langchain/core/utils/tiktoken");
      return;
    }

    // 用一个永远失败的 Promise 替换 getEncoding
    // 这样 fetch 立即抛错，避免 30s DNS 超时
    mod.getEncoding = async function (_encoding: string) {
      throw new Error("tiktoken network access is disabled in offline mode");
    };
    mod.encodingForModel = async function (_model: string) {
      throw new Error("tiktoken network access is disabled in offline mode");
    };

    console.log("[tiktoken-network] ✅ Disabled tiktoken network fetch (offline mode)");
  } catch (e) {
    console.warn("[tiktoken-network] Failed to install:", (e as Error).message);
  }
}
