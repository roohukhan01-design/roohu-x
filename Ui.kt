package com.roohux.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

val Accent = Color(0xFFB388FF)
val Pink = Color(0xFFFF4D8D)
val Ink = Color(0xFF0A0A12)
val Muted = Color(0xFFB8B3CC)
val AppBg = Brush.verticalGradient(listOf(Color(0xFF2B1457), Color(0xFF14102B), Ink))

@Composable
fun RoohuApp(vm: AppVm) {
    var tab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    LaunchedEffect(vm.message) { vm.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.message = null } }
    val cur = vm.current
    var last by remember { mutableStateOf(cur) }
    if (cur != null) last = cur
    BackHandler(enabled = cur != null) { vm.close() }

    Box(Modifier.fillMaxSize().background(AppBg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { (fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 24 }) togetherWith fadeOut(tween(140)) },
                    label = "tab"
                ) { t ->
                    when (t) { 0 -> HomeScreen(vm); 1 -> SearchScreen(vm); 2 -> LibraryScreen(vm); else -> LocalScreen(vm) }
                }
            }
            NavBar(tab) { tab = it }
        }
        AnimatedVisibility(
            visible = cur != null,
            enter = slideInVertically(tween(380)) { it } + fadeIn(tween(380)),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(300))
        ) { last?.let { PlayerScreen(vm, it) } }
    }
}

@Composable
fun NavBar(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("Home" to Icons.Rounded.Home, "Search" to Icons.Rounded.Search, "Library" to Icons.Rounded.LibraryMusic, "Local" to Icons.Rounded.PhoneAndroid)
    Row(
        Modifier.fillMaxWidth().background(Color(0xF00A0A12)).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEachIndexed { i, (label, icon) ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) Accent.copy(alpha = .22f) else Color.Transparent, tween(250), label = "bg")
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(bg).clickable { onSelect(i) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, label, tint = if (sel) Accent else Muted)
                AnimatedVisibility(sel) { Text(label, color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp)) }
            }
        }
    }
}

@Composable
fun ItemRow(i: Item, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = i.thumb, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(if (i.kind == "channel") CircleShape else RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF))
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(i.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when (i.kind) { "channel" -> "Artist / channel"; "playlist" -> "Playlist · ${i.sub}"; else -> i.sub }
            Text(sub, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
fun ItemList(
    s: UiState, retry: (() -> Unit)?, modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {}, trailing: @Composable (Item) -> Unit = {}, onClick: (Item) -> Unit
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        item { header() }
        when (s) {
            UiState.Loading -> item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = Accent) } }
            is UiState.Err -> item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.msg, color = Color.White, textAlign = TextAlign.Center)
                    if (retry != null) { Spacer(Modifier.height(12.dp)); FilledTonalButton(onClick = retry) { Text("Retry") } }
                }
            }
            is UiState.Data -> items(s.items) { i -> ItemRow(i, { onClick(i) }) { trailing(i) } }
            UiState.Idle -> {}
        }
    }
}

@Composable
fun Title(t: String, sub: String? = null) {
    Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
        Text(t, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
        if (sub != null) Text(sub, color = Muted)
    }
}

@Composable
fun HomeScreen(vm: AppVm) {
    ItemList(vm.trending, vm::loadTrending, Modifier.statusBarsPadding().padding(horizontal = 16.dp),
        header = { Title("RoohuX", "Trending music") }) { vm.play(it) }
}

@Composable
fun SearchScreen(vm: AppVm) {
    val kb = LocalSoftwareKeyboardController.current
    val recents by vm.searches.collectAsState()
    Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
        Title("Search")
        OutlinedTextField(
            value = vm.query, onValueChange = { vm.query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Songs, artists, playlists…") }, shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (vm.query.isNotEmpty()) IconButton({ vm.query = ""; vm.resetSearch() }) { Icon(Icons.Rounded.Close, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(); kb?.hide() })
        )
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.values().forEach { f ->
                FilterChip(vm.filter == f, { vm.filter = f; if (vm.query.isNotBlank()) vm.search() }, { Text(f.label, maxLines = 1, fontSize = 12.sp) })
            }
        }
        if (vm.results == UiState.Idle) {
            LazyColumn {
                if (recents.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", color = Muted); TextButton(vm::clearSearches) { Text("Clear") }
                    }
                }
                items(recents) { r ->
                    Row(Modifier.fillMaxWidth().clickable { vm.query = r.q; vm.search(); kb?.hide() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, tint = Muted); Spacer(Modifier.width(12.dp)); Text(r.q, color = Color.White)
                    }
                }
            }
        } else {
            ItemList(vm.results, { if (vm.collectionTitle == null) vm.search() else vm.resetSearch() },
                header = {
                    vm.collectionTitle?.let { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(vm::resetSearch) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
                            Text(t, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }) { vm.open(it) }
        }
    }
}

@Composable
fun LibraryScreen(vm: AppVm) {
    var sub by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Pl?>(null) }
    var dlg by remember { mutableStateOf(false) }
    val favs by vm.favs.collectAsState()
    val hist by vm.hist.collectAsState()
    val pls by vm.playlists.collectAsState()
    fun listState(l: List<Item>, empty: String): UiState = if (l.isEmpty()) UiState.Err(empty) else UiState.Data(l)

    Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
        Title("Library")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Favorites", "Playlists", "Recent").forEachIndexed { i, t -> FilterChip(sub == i, { sub = i; open = null }, { Text(t) }) }
        }
        Spacer(Modifier.height(8.dp))
        when {
            sub == 0 -> ItemList(listState(favs.map { it.toItem() }, "No favorites yet. Tap the heart in the player."), null) { vm.play(it) }
            sub == 2 -> ItemList(listState(hist.map { it.toItem() }, "Nothing played yet."), null) { vm.play(it) }
            open != null -> {
                val pl = open!!
                val items by remember(pl.id) { vm.plItems(pl.id) }.collectAsState(emptyList())
                ItemList(listState(items.map { it.toItem() }, "This playlist is empty. Add songs from the player."), null,
                    header = { TextButton({ open = null }) { Text("← All playlists") } },
                    trailing = { i -> IconButton({ vm.removeFromPlaylist(pl.id, i.id) }) { Icon(Icons.Rounded.Delete, "Remove", tint = Muted) } }) { vm.play(it) }
            }
            else -> LazyColumn {
                item { FilledTonalButton({ dlg = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New playlist") } }
                if (pls.isEmpty()) item { Text("No playlists yet.", color = Muted, modifier = Modifier.padding(16.dp)) }
                items(pls) { p ->
                    Row(Modifier.fillMaxWidth().clickable { open = p }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.QueueMusic, null, tint = Accent); Spacer(Modifier.width(12.dp))
                        Text(p.name, color = Color.White, modifier = Modifier.weight(1f))
                        IconButton({ vm.deletePlaylist(p.id) }) { Icon(Icons.Rounded.Delete, "Delete", tint = Muted) }
                    }
                }
            }
        }
    }
    if (dlg) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { dlg = false }, title = { Text("New playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
            confirmButton = { TextButton({ vm.createPlaylist(name); dlg = false }) { Text("Create") } },
            dismissButton = { TextButton({ dlg = false }) { Text("Cancel") } })
    }
}

@Composable
fun LocalScreen(vm: AppVm) {
    val locals by vm.locals.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importLocal(it) }
    var url by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    LazyColumn(Modifier.statusBarsPadding().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Title("Local music", "Plays in the background with notification controls") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ picker.launch(arrayOf("audio/*")) }) { Icon(Icons.Rounded.AudioFile, null); Spacer(Modifier.width(6.dp)); Text("Add audio files") }
            }
        }
        if (vm.localTitle.isNotBlank()) item {
            Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x33B388FF)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(vm.localTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(vm::localPrev) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White) }
                IconButton(vm::toggleLocal) { Icon(if (vm.localPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Color.White) }
                IconButton(vm::localNext) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White) }
            }
        }
        if (locals.isEmpty()) item { Text("No local tracks yet. Add audio files from your device.", color = Muted, modifier = Modifier.padding(vertical = 16.dp)) }
        itemsIndexed(locals) { idx, t ->
            Row(Modifier.fillMaxWidth().clickable { vm.playLocal(locals, idx) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, null, tint = Accent); Spacer(Modifier.width(12.dp))
                Text(t.title, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ vm.removeLocal(t) }) { Icon(Icons.Rounded.Delete, "Remove", tint = Muted) }
            }
        }
        item {
            Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x22FFFFFF)).padding(16.dp)) {
                Text("Download a file", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Direct links to audio you're allowed to save. YouTube videos can't be downloaded.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, placeholder = { Text("https://…/track.mp3") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ok, { ok = it }); Text("I have the right to save this file", color = Color.White, fontSize = 13.sp)
                }
                Button({ vm.download(url) }, enabled = ok && url.isNotBlank()) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Download") }
            }
        }
    }
}

private fun errText(e: PlayerConstants.PlayerError) = when (e) {
    PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> "This video was removed or is private."
    PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> "The owner doesn't allow this video to play in other apps."
    else -> "Playback failed (${e.name}). Check your connection."
}

@Composable
fun YtPlayer(id: String, onError: (String) -> Unit, onEnded: () -> Unit) {
    val lo = LocalLifecycleOwner.current
    val holder = remember { arrayOfNulls<YouTubePlayer>(1) }
    val curId = rememberUpdatedState(id)
    val onErr = rememberUpdatedState(onError)
    val onEnd = rememberUpdatedState(onEnded)
    var view by remember { mutableStateOf<YouTubePlayerView?>(null) }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        YouTubePlayerView(ctx).apply {
            enableAutomaticInitialization = false
            lo.lifecycle.addObserver(this)
            // controls(1) keeps the player's own control bar, including whatever settings/quality menu YouTube offers.
            initialize(object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) { holder[0] = youTubePlayer; youTubePlayer.loadVideo(curId.value, 0f) }
                override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) { onErr.value(errText(error)) }
                override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                    if (state == PlayerConstants.PlayerState.ENDED) onEnd.value()
                }
            }, IFramePlayerOptions.Builder().controls(1).build())
            view = this
        }
    })
    LaunchedEffect(id) { holder[0]?.loadVideo(id, 0f) }
    DisposableEffect(Unit) { onDispose { view?.let { lo.lifecycle.removeObserver(it); it.release() } } }
}

@Composable
fun PlayerScreen(vm: AppVm, item: Item) {
    val favs by vm.favs.collectAsState()
    val pls by vm.playlists.collectAsState()
    val isFav = favs.any { it.videoId == item.id }
    var showPl by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(Ink)) {
        AsyncImage(item.thumb, null, Modifier.fillMaxSize().blur(40.dp).alpha(.4f), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Ink))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(vm::close) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = Color.White, modifier = Modifier.size(32.dp)) }
                Text("Now playing", color = Muted)
            }
            Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                key(item.id.isNotEmpty()) { YtPlayer(item.id, onError = { vm.playError = it }, onEnded = vm::playNext) }
            }
            vm.playError?.let { msg ->
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x33FF4D8D)).padding(12.dp)) {
                    Text(msg, color = Color.White)
                    Row {
                        TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${item.id}"))) }) { Text("Open in YouTube") }
                        TextButton(vm::playNext) { Text("Skip") }
                    }
                }
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.sub, color = Muted, maxLines = 1)
                }
                IconButton({ vm.toggleFav(item) }) { Icon(if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite", tint = if (isFav) Pink else Color.White) }
                IconButton({ showPl = true }) { Icon(Icons.Rounded.PlaylistAdd, "Add to playlist", tint = Color.White) }
            }
            Text("Related", color = Muted, modifier = Modifier.padding(horizontal = 16.dp))
            ItemList(vm.related, null, Modifier.weight(1f).padding(horizontal = 8.dp)) { vm.play(it) }
        }
    }
    if (showPl) AlertDialog(
        onDismissRequest = { showPl = false }, title = { Text("Add to playlist") },
        text = {
            if (pls.isEmpty()) Text("Create a playlist in the Library tab first.")
            else Column { pls.forEach { p -> TextButton({ vm.addToPlaylist(p.id, item); showPl = false }) { Text(p.name) } } }
        },
        confirmButton = { TextButton({ showPl = false }) { Text("Close") } })
}
