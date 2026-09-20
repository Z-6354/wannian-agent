package com.wannian.server.kernel.backup;

import java.nio.file.Path;

/**
 * 当前库的一致性快照。
 *
 * <p>实现必须使用数据库自己的备份能力，禁止复制正在写入的主文件或 WAL。
 * 快照写到调用方给出的目录，不得覆盖正在使用的库。
 */
public interface ConsistencyBackup {

    /**
     * 导出一份可单独打开的快照。
     *
     * @param targetDirectory 快照目录；不存在则创建。其中的库文件名固定为 {@code wannian.db}
     * @return 快照位置与元数据；不得为 null
     * @throws IllegalArgumentException 目标会覆盖正在使用的库，或快照文件已存在
     */
    BackupSnapshot createSnapshot(Path targetDirectory);
}
