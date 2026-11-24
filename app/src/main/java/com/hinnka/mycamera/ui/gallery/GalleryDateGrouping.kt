package com.hinnka.mycamera.ui.gallery

import android.content.Context
import android.text.format.DateUtils
import com.hinnka.mycamera.R
import com.hinnka.mycamera.gallery.MediaData
import com.hinnka.mycamera.viewmodel.GalleryTab
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

internal sealed interface GalleryGridEntry {
    data class Header(
        val key: String,
        val title: String,
        val photos: List<MediaData>
    ) : GalleryGridEntry

    data class Photo(
        val photo: MediaData,
        val index: Int,
        val sourceTab: GalleryTab = GalleryTab.PHOTON,
        val sourceIndex: Int = index
    ) : GalleryGridEntry
}

internal fun buildGalleryGridEntries(
    context: Context,
    photos: List<MediaData>,
    photonPhotos: List<MediaData> = emptyList(),
    systemPhotos: List<MediaData> = emptyList()
): List<GalleryGridEntry> {
    if (photos.isEmpty()) return emptyList()

    val photonIndexById = photonPhotos.withIndex().associate { it.value.id to it.index }
    val systemIndexById = systemPhotos.withIndex().associate { it.value.id to it.index }
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)

    return buildList {
        photos
            .withIndex()
            .groupBy { indexedPhoto -> indexedPhoto.value.dateAdded.toLocalDate(zoneId) }
            .toSortedMap(compareByDescending { it })
            .forEach { (date, groupedPhotos) ->
                add(
                    GalleryGridEntry.Header(
                        key = "header_$date",
                        title = formatGalleryGroupTitle(context, date.atStartOfDay(zoneId), today),
                        photos = groupedPhotos.map { it.value }
                    )
                )
                groupedPhotos.forEach { indexedPhoto ->
                    add(
                        val photoId = indexedPhoto.value.id
                        val systemIndex = systemIndexById[photoId]
                        GalleryGridEntry.Photo(
                            photo = indexedPhoto.value,
                            index = indexedPhoto.index,
                            sourceTab = if (systemIndex != null) GalleryTab.SYSTEM else GalleryTab.PHOTON,
                            sourceIndex = systemIndex ?: photonIndexById[photoId] ?: indexedPhoto.index
                        )
                    )
                }
            }
    }
}

private fun formatGalleryGroupTitle(
    context: Context,
    targetDate: ZonedDateTime,
    today: LocalDate
): String {
    val localDate = targetDate.toLocalDate()
    val daysDiff = ChronoUnit.DAYS.between(localDate, today)
    return when (daysDiff) {
        0L -> context.getString(R.string.gallery_group_today)
        1L -> context.getString(R.string.gallery_group_yesterday)
        else -> {
            val flags = if (localDate.year == today.year) {
                DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_SHOW_WEEKDAY or
                    DateUtils.FORMAT_NO_YEAR
            } else {
                DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_SHOW_WEEKDAY or
                    DateUtils.FORMAT_SHOW_YEAR
            }
            DateUtils.formatDateTime(context, targetDate.toInstant().toEpochMilli(), flags)
        }
    }
}

private fun Long.toLocalDate(zoneId: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()
