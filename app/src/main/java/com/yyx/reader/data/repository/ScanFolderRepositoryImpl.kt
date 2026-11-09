package com.yyx.reader.data.repository

import com.yyx.reader.core.database.dao.ScanFolderDao
import com.yyx.reader.core.database.entity.ScanFolderEntity
import com.yyx.reader.core.domain.model.ScanFolder
import com.yyx.reader.core.domain.repository.ScanFolderRepository
import com.yyx.reader.data.toDomain
import com.yyx.reader.data.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ScanFolderRepositoryImpl @Inject constructor(
    private val scanFolderDao: ScanFolderDao,
) : ScanFolderRepository {

    override fun observeFolders(): Flow<List<ScanFolder>> =
        scanFolderDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun add(treeUri: String, displayName: String): Boolean =
        scanFolderDao.insert(
            ScanFolderEntity(
                treeUri = treeUri,
                displayName = displayName,
                addedAt = System.currentTimeMillis(),
            ),
        ) != -1L

    override suspend fun remove(folder: ScanFolder) {
        scanFolderDao.delete(folder.toEntity())
    }
}
