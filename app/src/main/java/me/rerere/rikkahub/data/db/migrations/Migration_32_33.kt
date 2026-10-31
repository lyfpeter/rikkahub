package me.rerere.rikkahub.data.db.migrations

import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec

/** 移除工作区与媒体生成功能后，删掉对应的表 */
@DeleteTable.Entries(
    DeleteTable(tableName = "workspaces"),
    DeleteTable(tableName = "media_creation_session"),
    DeleteTable(tableName = "media_creation_node"),
    DeleteTable(tableName = "media_creation_record"),
)
class Migration_32_33 : AutoMigrationSpec
