/**
 * 唯一 ID 生成器
 * 用于生成 Block、Component 等的唯一标识符
 */

export class UniqueIdGenerator {
  private static counter = 0;
  private static prefix = 'id';

  /**
   * 生成唯一 ID
   */
  static generate(prefix?: string): string {
    const actualPrefix = prefix || this.prefix;
    const timestamp = Date.now().toString(36);
    const counter = (++this.counter).toString(36);
    const random = Math.random().toString(36).substr(2, 5);

    return `${actualPrefix}_${timestamp}_${counter}_${random}`;
  }

  /**
   * 生成短 ID（用于临时标识）
   */
  static generateShort(prefix?: string): string {
    const actualPrefix = prefix || this.prefix;
    const counter = (++this.counter).toString(36);
    const random = Math.random().toString(36).substr(2, 3);

    return `${actualPrefix}_${counter}_${random}`;
  }

  /**
   * 验证 ID 格式
   */
  static isValid(id: string): boolean {
    return typeof id === 'string' && id.length > 0 && /^[a-zA-Z0-9_]+$/.test(id);
  }

  /**
   * 重置计数器
   */
  static reset(): void {
    this.counter = 0;
  }

  /**
   * 设置默认前缀
   */
  static setPrefix(prefix: string): void {
    this.prefix = prefix;
  }
}

export default UniqueIdGenerator;
