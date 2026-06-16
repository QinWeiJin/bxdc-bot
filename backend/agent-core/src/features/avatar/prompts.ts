/**
 * AI 头像必须与前端自托管 Twemoji 子集一致（见 `frontend/src/constants/twemojiCoveredEmoji.ts`）。
 */
export const GENERATE_AVATAR_SYSTEM_PROMPT = `
You are an expert at selecting the perfect emoji to represent a user based on their nickname.
Your goal is to return a SINGLE emoji character that best captures the essence, meaning, or vibe of the nickname.

Rules:
1. Return ONLY the emoji character. No text, no markdown, no explanation.
2. You MUST choose from this exact set and no others:
   👤 🤖 😀 😊 😎 🥳 🤓 🐱 🐶 🦊 🐻 🐼 🐨 🦁 🐸 🦄 🐧 🐙 🌸 🌙 ⭐ 🌈 🔥 💧 🚀 🎨 📚 🎮 🍎 ☕ 🎁 💎 🔔 🌊 🌻 🍀 🎸 🏀 ⚽ 🎲 🔌 🧮 ✨ 🧩 ⚙️ 📦 🛠️ 🔧 📡 🎯 💡 🌐 📎 🤔
3. Pick the best match from that set for the nickname (e.g. tech vibe -> pick from tools; default human -> 👤; playful bot -> 🤖).
4. If none fits well, use 👤.
5. Do NOT use offensive or inappropriate emojis.
`;

export const GREETING_TEMPLATES: string[] = [
  "你好，{nickname} {avatar}！今天想聊点什么？",
  "欢迎回来，{nickname} {avatar}！有什么可以帮你的？",
  "嗨，{nickname} {avatar}！很高兴见到你~",
  "{nickname} {avatar}，欢迎来到 BRDC.bot！开始一场有趣的对话吧。",
  "Hey，{nickname} {avatar}！准备好探索新知识了吗？",
  "又见面啦，{nickname} {avatar}！今天有什么新想法？",
  "{nickname} {avatar}，欢迎光临！我是你的 AI 助手，随时待命。",
  "哈喽，{nickname} {avatar}！期待和你碰撞出思维的火花。",
  "你好呀，{nickname} {avatar}！放松心情，随便聊聊吧。",
  "{nickname} {avatar}，欢迎加入！这里没有傻问题，只有好奇的心。",
  "嗨嗨，{nickname} {avatar}！一天的好心情从聊天开始~",
  "欢迎你，{nickname} {avatar}！希望今天能帮到你点什么。",
  "{nickname} {avatar}，来啦！坐下来聊聊天呗。",
  "你好，{nickname} {avatar}！无论大事小事，我都乐意倾听。",
  "哈喽 {nickname} {avatar}！每一次对话都是一次小小的冒险。",
  "{nickname} {avatar}，欢迎上船！我们一起探索未知的领域。",
  "嗨，{nickname} {avatar}！新对话，新开始，加油！",
  "欢迎欢迎，{nickname} {avatar}！今天的天气是「适合聊天」。",
  "{nickname} {avatar}，你来啦！我已经准备好接招了。",
  "你好，{nickname} {avatar}！用心倾听，认真回答，这是我的承诺。",
  "嘿，{nickname} {avatar}！很高兴成为你的今日搭子。",
  "{nickname} {avatar}，欢迎回家！这里永远有一盏灯为你亮着。",
  "哈喽 {nickname} {avatar}！放下包袱，畅所欲言吧。",
  "你好，{nickname} {avatar}！世界上有两种东西藏不住——喷嚏和好奇心。而你，显然两者都有。",
  "{nickname} {avatar}，新的对话已开启！让我们从一句「你好」开始吧。",
  "嗨，{nickname} {avatar}！时间很宝贵，但和你聊天值得。",
  "欢迎你，{nickname} {avatar}！今天想搞点技术还是聊聊人生？",
  "{nickname} {avatar}，看到你来了，这一刻值得记录。",
  "你好呀，{nickname} {avatar}！别客气，把我当成你的私人智囊团。",
  "{nickname} {avatar}，欢迎光临 BRDC.bot！愿你在这里找到所有答案。",
];
