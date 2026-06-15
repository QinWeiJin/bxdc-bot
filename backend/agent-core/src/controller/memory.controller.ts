/**
 * 记忆管理控制器
 *
 * 模块职责：
 * 1. 提供长期记忆的手动添加端点
 * 2. 允许外部系统直接向 mem0 服务添加记忆
 * 3. 主要用于调试和批量导入场景
 * 4. 提供「清除记忆」与「状态查询」端点（配合资料编辑页"清除 → 重新初始化"流程）
 *
 * 注意：
 * - 正常情况下记忆通过 Agent 对话自动提取和存储
 * - 本控制器提供的 addMemory 端点用于特殊场景的手动干预
 *
 * 端点说明：
 * - POST /memory/add: 添加一条记忆到用户的长期记忆中（带跨用户守卫）
 * - POST /memory/delete: 按 userid 全量删除该用户的长期记忆（带跨用户守卫）
 * - GET /memory/status?userId=xxx: 查询记忆功能状态（{ enabled: boolean }，不带 hasMemory）
 *
 * 跨用户守卫（spec mem0-integration + memory-initialization-flow）：
 *   POST /memory/add 和 POST /memory/delete 必须校验请求体中的 userId 与
 *   X-User-Id 请求头一致，否则返回 403。这是为了防止请求方在 body 里冒用其他用户 id
 *   触发误删/误写。该机制依赖前端每次都如实发送 X-User-Id 头（=当前用户 id）。
 *   项目尚无 session-based auth，所以这是"弱守卫"——spec 锁定此规则作为后续升级
 *   session-based auth 的占位语义。
 *
 * 依赖服务：
 * - MemoryService: 提供记忆的增删改查能力
 *
 * @module MemoryController
 * @author Agent Core Team
 * @since 1.0.0
 */

import { Controller, Post, Get, Body, Query, Req, HttpException, HttpStatus } from '@nestjs/common';
import { Request } from 'express';
import { MemoryService } from '../mem/memory.service';

interface AuthedRequest extends Request {
  headers: Request['headers'] & {
    'x-user-id'?: string;
  };
}

/**
 * 校验 body / query 中的 userId 与 X-User-Id header 是否一致。
 * 不一致 → 抛 403 Forbidden。
 * 未提供 X-User-Id → 同样 403（强模式，避免 frontend 漏送 header 时静默放行）。
 */
function assertUserMatchesHeader(req: AuthedRequest, claimedUserId: string | undefined): void {
  const headerUserId = req.headers['x-user-id'];
  if (!claimedUserId) {
    throw new HttpException(
      { code: 400, message: 'userId is required' },
      HttpStatus.BAD_REQUEST
    );
  }
  if (!headerUserId) {
    throw new HttpException(
      {
        code: 403,
        message: 'X-User-Id header is required for this operation (cross-user guard)'
      },
      HttpStatus.FORBIDDEN
    );
  }
  if (headerUserId !== claimedUserId) {
    throw new HttpException(
      {
        code: 403,
        message: `X-User-Id header (${headerUserId}) does not match body userId (${claimedUserId})`
      },
      HttpStatus.FORBIDDEN
    );
  }
}

/**
 * 记忆管理控制器类
 *
 * 装饰器说明：
 * @Controller('memory') - 基础路径为 /memory
 */
@Controller('memory')
export class MemoryController {
  constructor(private readonly memoryService: MemoryService) {}

  /**
   * 手动添加记忆端点
   *
   * 使用场景：
   * - 调试记忆功能
   * - 批量导入用户画像数据
   * - 管理员手动修复错误记忆
   * - 资料编辑页「清除 → 重新初始化」流程的"初始化"步骤
   *
   * 请求体：
   * - userId: 用户唯一标识符（必须与 X-User-Id header 一致）
   * - text: 记忆内容文本
   * - role: 发言者角色（可选，默认 'system'；当前在 addMemory 内 no-op，预留未来扩展）
   *
   * @param body - 记忆添加请求体
   * @returns 添加结果
   */
  @Post('add')
  async addMemory(
    @Req() req: AuthedRequest,
    @Body() body: { userId: string; text: string; role?: 'user' | 'assistant' | 'system' }
  ) {
    assertUserMatchesHeader(req, body.userId);
    console.log('[MemoryController] Adding memory:', body);
    return this.memoryService.addMemory(body.userId, body.text, body.role);
  }

  /**
   * 全量删除用户长期记忆端点
   *
   * 使用场景：
   * - 资料编辑页「清除 → 重新初始化」流程的"清空"步骤
   *
   * 请求体：
   * - userId: 用户唯一标识符（必须与 X-User-Id header 一致）
   *
   * @param body - 记忆删除请求体
   * @returns 删除结果
   */
  @Post('delete')
  async deleteMemory(
    @Req() req: AuthedRequest,
    @Body() body: { userId: string }
  ) {
    assertUserMatchesHeader(req, body.userId);
    console.log('[MemoryController] Deleting all memories for user:', body.userId);
    try {
      await this.memoryService.deleteAllMemories(body.userId);
      return { code: 200, message: '记忆已清空', userId: body.userId };
    } catch (e: any) {
      throw new HttpException(
        { code: 500, message: e?.message || '清空失败' },
        HttpStatus.INTERNAL_SERVER_ERROR
      );
    }
  }

  /**
   * 查询记忆功能状态
   *
   * 始终返回 { enabled: boolean }（不带 hasMemory 字段）。
   * enabled 来自 MEM0_ENABLED 环境变量（与 spec memory-initialization-flow Decision 7 一致）。
   * 不调 mem0，不抛错。
   *
   * 注意：此端点**没有**跨用户守卫，因为：
   *   - 不修改任何用户数据
   *   - 只返回全局开关
   *   - spec 仅要求 enable 渲染，不要求"用户隔离"
   *
   * @param userId - 用户唯一标识符（保留入参以兼容 spec，但 spec 不强制校验）
   * @returns 状态对象
   */
  @Get('status')
  async getStatus(@Query('userId') userId: string) {
    try {
      const status = await this.memoryService.getMemoryStatus(userId);
      return status;
    } catch (e: any) {
      // spec 锁定：mem0 不可达时也返回 enabled: false（不抛错）
      console.warn('[MemoryController] getMemoryStatus fallback to disabled:', e?.message);
      return { enabled: false };
    }
  }

  @Get('profile')
  async getProfile(@Query('userId') userId: string) {
    const details = await this.memoryService.fetchUserProfile(userId);
    return { userId, details, success: !!details };
  }
}
