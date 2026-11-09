package com.yyx.reader.data

import com.yyx.reader.core.database.entity.BookEntity
import com.yyx.reader.core.database.entity.ChapterEntity
import com.yyx.reader.core.database.entity.ReadingProgressEntity
import com.yyx.reader.core.database.entity.ScanFolderEntity
import com.yyx.reader.core.domain.model.Book
import com.yyx.reader.core.domain.model.BookFormat
import com.yyx.reader.core.domain.model.BookPosition
import com.yyx.reader.core.domain.model.Chapter
import com.yyx.reader.core.domain.model.ProgressSource
import com.yyx.reader.core.domain.model.ScanFolder

internal fun BookEntity.toDomain(): Book = Book(
    id = id,
    title = title,
    author = author,
    summary = summary,
    format = BookFormat.entries.firstOrNull { it.name.equals(format, true) } ?: BookFormat.TXT,
    fileUri = fileUri,
    charset = charset,
    coverPath = coverPath,
    chapterCount = chapterCount,
    wordCount = wordCount,
    fileMtime = fileMtime,
    fileSize = fileSize,
    lastReadAt = lastReadAt,
    addedAt = addedAt,
    updatedAt = updatedAt,
)

internal fun Book.toEntity(now: Long): BookEntity = BookEntity(
    id = id,
    title = title,
    author = author,
    summary = summary,
    format = format.name.lowercase(),
    fileUri = fileUri,
    charset = charset,
    coverPath = coverPath,
    chapterCount = chapterCount,
    wordCount = wordCount,
    fileMtime = fileMtime,
    fileSize = fileSize,
    lastReadAt = lastReadAt,
    addedAt = if (addedAt > 0) addedAt else now,
    updatedAt = now,
)

internal fun ChapterEntity.toDomain(): Chapter = Chapter(
    id = id,
    bookId = bookId,
    idx = idx,
    title = title,
    startOffset = startOffset,
    endOffset = endOffset,
    charCount = charCount,
    epubHref = epubHref,
)

internal fun Chapter.toEntity(bookId: Long): ChapterEntity = ChapterEntity(
    bookId = bookId,
    idx = idx,
    title = title,
    startOffset = startOffset,
    endOffset = endOffset,
    charCount = charCount,
    epubHref = epubHref,
)

internal fun ReadingProgressEntity.toDomain(): com.yyx.reader.core.domain.model.ProgressInfo =
    com.yyx.reader.core.domain.model.ProgressInfo(
        position = BookPosition(bookId, chapterIdx, charOffset),
        percent = percent,
        source = ProgressSource.entries.firstOrNull { it.name.equals(source, true) }
            ?: ProgressSource.READ,
        updatedAt = updatedAt,
    )

internal fun ScanFolderEntity.toDomain(): ScanFolder = ScanFolder(
    id = id,
    treeUri = treeUri,
    displayName = displayName,
    addedAt = addedAt,
    lastScanAt = lastScanAt,
)

internal fun ScanFolder.toEntity(): ScanFolderEntity = ScanFolderEntity(
    id = id,
    treeUri = treeUri,
    displayName = displayName,
    addedAt = addedAt,
    lastScanAt = lastScanAt,
)
