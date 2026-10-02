// Shizuku UserService 接口。
// 该接口的实现运行在 Shizuku 拉起的 shell(2000) 进程中，可直接访问 Android/data 等受限目录。
// 新版 Shizuku 服务端已禁用 Shizuku.newProcess()，文件访问统一走这里。
package com.fuckets.etsviewer;

interface IUserFsService {

    /**
     * 批量扫描目录：一次 IPC 返回 basePath 下所有子文件夹的 "名称\u0001修改时间秒\u0001content.json 内容"。
     * 分页返回（Binder 事务有 ~1MB 上限，全量一次返回可能抛 TransactionTooLargeException）：
     * 从 offset 起最多返回 limit 条，客户端循环取直到返回数量 < limit。
     * 无 content.json 或读取失败时内容字段为空串。失败返回 null。
     */
    String[] loadEntries(String basePath, int offset, int limit);

    /** 列出目录下的子文件夹，每项格式 "名称|修改时间秒"；失败返回 null */
    String[] listFolders(String path);

    /** 读取文本文件内容；失败返回 null */
    String readFile(String path);

    /** 以 sh -c 执行命令并返回 stdout；exit != 0 时返回 null */
    String exec(String cmd);

    /** 递归删除文件或目录，返回是否删除成功 */
    boolean deleteRecursively(String path);

    /** 路径是否存在 */
    boolean exists(String path);

    /** 最近一次失败的原因（供客户端记录日志） */
    String lastError();

    /** 解绑时由 Shizuku 调用，用于释放资源 */
    void destroy();
}
