package org.totschnig.myexpenses.viewmodel

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import app.cash.copper.flow.mapToList
import app.cash.copper.flow.observeQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.collections4.ListUtils
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_COUNT
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_LABEL
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_ROWID
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.provider.appendBooleanQueryParameter
import org.totschnig.myexpenses.viewmodel.data.Tag

class TagListViewModel(application: Application, savedStateHandle: SavedStateHandle) :
    TagBaseViewModel(application, savedStateHandle) {
    private val tagsInternal = MutableLiveData<List<Tag>>()
    val tags: LiveData<List<Tag>> = tagsInternal

    fun loadTags(selected: List<Tag>?) {
        viewModelScope.launch(context = coroutineContext()) {
        if (tagsInternal.value == null) {
            val tagsUri = TransactionProvider.TAGS_URI.buildUpon()
                .appendBooleanQueryParameter(TransactionProvider.QUERY_PARAMETER_WITH_COUNT).build()
            contentResolver.observeQuery(
                uri = tagsUri,
                sortOrder = "$KEY_LABEL COLLATE LOCALIZED",
            ).mapToList { cursor ->
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ROWID))
                    val label = cursor.getString(cursor.getColumnIndexOrThrow(KEY_LABEL))
                    val count = cursor.getColumnIndex(KEY_COUNT).takeIf { it > -1 }
                        ?.let { cursor.getInt(it) }
                        ?: -1
                    Tag(id, label, selected?.find { tag -> tag.label == label } != null, count)
                }.collect { list ->
                tagsInternal.postValue(selected?.let {
                    ListUtils.union(
                        it.filter { tag -> tag.id == -1L },
                        list
                    )
                }
                    ?: list)
            }
        }
    }
    }

    fun removeTagAndPersist(tag: Tag) {
        viewModelScope.launch(context = coroutineContext()) {
            if (contentResolver.delete(
                    ContentUris.withAppendedId(
                        TransactionProvider.TAGS_URI,
                        tag.id
                    ), null, null
                ) == 1
            ) {
                addDeletedTagId(tag.id)
            }
        }
    }

    fun addTagAndPersist(label: String): LiveData<Boolean> =
        liveData(context = viewModelScope.coroutineContext + Dispatchers.IO) {
            val result = contentResolver.insert(TransactionProvider.TAGS_URI,
                ContentValues().apply { put(KEY_LABEL, label) })
            emit(result != null)
        }

    fun updateTag(tag: Tag, newLabel: String) =
        liveData(context = viewModelScope.coroutineContext + Dispatchers.IO) {
            val result = try {
                contentResolver.update(ContentUris.withAppendedId(
                    TransactionProvider.TAGS_URI,
                    tag.id
                ),
                    ContentValues().apply { put(KEY_LABEL, newLabel) }, null, null
                )
            } catch (e: SQLiteConstraintException) {
                0
            }
            val success = result == 1
            if (success) {
                tagsInternal.postValue(tagsInternal.value?.map {
                    if (it == tag) Tag(
                        tag.id,
                        newLabel,
                        tag.selected,
                        tag.count
                    ) else it
                })
            }
            emit(success)
        }
}