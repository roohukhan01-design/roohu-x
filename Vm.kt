package com.roohux.app

import android.app.Application
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState {
    object Idle : UiState
    object Loading : UiState
    data class Err(val msg: String) : UiState
    data class Data(val items: List<Item>) : UiState
}

class AppVm(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()
    private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.hot() = stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favs = dao.favs().hot()
    val hist = dao.hist().hot()
    val searches = dao.searches().hot()
    val playlists = dao.playlists().hot()
    val locals = dao.locals().hot()
    fun plItems(id: Long) = dao.plItems(id)

    var message by mutableStateOf<String?>(null)

    // Search / discovery
    var query by mutableStateOf("")
    var filter by mutableStateOf(Filter.Songs)
    var results by mutableStateOf<UiState>(UiState.Idle)
    var collectionTitle by mutableStateOf<String?>(null)
    var trending by mutableStateOf<UiState>(UiState.Loading)
    private var job: Job? = null

    private fun load(set: (UiState) -> Unit, block: suspend () -> List<Item>) {
        job?.cancel()
        set(UiState.Loading)
        job = viewModelScope.launch {
            set(try {
                val l = block()
                if (l.isEmpty()) UiState.Err("Nothing found.") else UiState.Data(l)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { UiState.Err(e.message ?: "Something went wrong.") })
        }
    }

    fun loadTrending() {
        trending = UiState.Loading
        viewModelScope.launch {
            trending = try { val l = Api.trending(); if (l.isEmpty()) UiState.Err("No trending music available for your region.") else UiState.Data(l) }
            catch (e: Exception) { UiState.Err(e.message ?: "Something went wrong.") }
        }
    }

    fun search() {
        val q = query.trim(); if (q.isEmpty()) return
        collectionTitle = null
        viewModelScope.launch { dao.addSearch(SearchQ(q, System.currentTimeMillis())) }
        val f = filter
        load({ results = it }) { Api.search(q, f) }
    }

    fun clearSearches() { viewModelScope.launch { dao.clearSearches() } }
    fun resetSearch() { job?.cancel(); results = UiState.Idle; collectionTitle = null }

    fun open(i: Item) {
        when (i.kind) {
            "video" -> play(i)
            "channel" -> { collectionTitle = i.title; load({ results = it }) { Api.channelVideos(i.id) } }
            "playlist" -> { collectionTitle = i.title; load({ results = it }) { Api.playlistItems(i.id) } }
        }
    }

    // YouTube playback (embedded player)
    var current by mutableStateOf<Item?>(null)
    var related by mutableStateOf<UiState>(UiState.Idle)
    var playError by mutableStateOf<String?>(null)
    private var relJob: Job? = null

    fun play(i: Item) {
        controller?.pause()
        current = i; playError = null
        viewModelScope.launch { dao.addHist(Hist(i.id, i.title, i.sub, i.thumb, System.currentTimeMillis())) }
        relJob?.cancel(); related = UiState.Loading
        relJob = viewModelScope.launch {
            related = try { val l = Api.related(i); if (l.isEmpty()) UiState.Err("No related videos.") else UiState.Data(l) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { UiState.Err(e.message ?: "Couldn't load related videos.") }
        }
    }
    fun playNext() { (related as? UiState.Data)?.items?.firstOrNull { it.id != current?.id }?.let { play(it) } }
    fun close() { current = null; playError = null }

    fun toggleFav(i: Item) = viewModelScope.launch {
        if (favs.value.any { it.videoId == i.id }) dao.delFav(i.id) else dao.addFav(Fav(i.id, i.title, i.sub, i.thumb, System.currentTimeMillis()))
    }
    fun createPlaylist(name: String) { if (name.isNotBlank()) viewModelScope.launch { dao.addPl(Pl(name = name.trim())) } }
    fun deletePlaylist(id: Long) { viewModelScope.launch { dao.delPlItems(id); dao.delPl(id) } }
    fun addToPlaylist(plId: Long, i: Item) { viewModelScope.launch { dao.addPlItem(PlItem(plId, i.id, i.title, i.sub, i.thumb)); message = "Added to playlist" } }
    fun removeFromPlaylist(plId: Long, vid: String) { viewModelScope.launch { dao.delPlItem(plId, vid) } }

    // Local files
    private var controller: MediaController? = null
    var localPlaying by mutableStateOf(false)
    var localTitle by mutableStateOf("")

    init {
        loadTrending()
        val ctx = app.applicationContext
        val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        f.addListener({
            try {
                val c = f.get(); controller = c
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { localPlaying = isPlaying }
                    override fun onMediaMetadataChanged(m: MediaMetadata) { localTitle = m.title?.toString().orEmpty() }
                    override fun onPlayerError(e: PlaybackException) { message = "Can't play this file (${e.errorCodeName})." }
                })
            } catch (e: Exception) { message = "Audio service unavailable: ${e.message}" }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun importLocal(uris: List<Uri>) {
        val cr = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { u ->
                runCatching { cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val name = runCatching {
                    cr.query(u, null, null, null, null)?.use { c ->
                        val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && ix >= 0) c.getString(ix) else null
                    }
                }.getOrNull() ?: "Audio"
                dao.addLocal(LocalTrack(u.toString(), name.substringBeforeLast('.')))
            }
        }
    }
    fun removeLocal(t: LocalTrack) { viewModelScope.launch { dao.delLocal(t.uri) } }

    fun playLocal(list: List<LocalTrack>, index: Int) {
        val c = controller ?: run { message = "Audio service is still starting. Try again."; return }
        current = null
        c.setMediaItems(list.map {
            MediaItem.Builder().setUri(it.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build()).build()
        }, index, 0L)
        c.prepare(); c.play()
    }
    fun toggleLocal() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun localNext() { controller?.seekToNext() }
    fun localPrev() { controller?.seekToPrevious() }

    /** Downloads only direct http(s) links to files the user has rights to. YouTube links are refused. */
    fun download(raw: String) {
        val u = runCatching { Uri.parse(raw.trim()) }.getOrNull()
        val host = u?.host?.lowercase()
        if (u == null || host == null || (u.scheme != "https" && u.scheme != "http")) { message = "Enter a direct http(s) link to an audio file."; return }
        if (host.contains("youtube") || host.contains("youtu.be") || host.contains("googlevideo")) {
            message = "YouTube content can't be downloaded. Use a direct link to a file you're allowed to save."; return
        }
        try {
            val name = (u.lastPathSegment ?: "track").substringAfterLast('/').ifBlank { "track" }
            val dm = getApplication<Application>().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(
                DownloadManager.Request(u).setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "RoohuX/$name")
            )
            message = "Download started. Saved to Music/RoohuX."
        } catch (e: Exception) { message = "Couldn't start download: ${e.message}" }
    }

    override fun onCleared() { controller?.release(); super.onCleared() }
}
