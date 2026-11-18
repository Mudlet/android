package org.mudlet.android.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [block] on [Dispatchers.IO] and resumes the caller from [main], for a coroutine of a composition: produceState,
 * LaunchedEffect or rememberCoroutineScope. On a device a composition's coroutines go back to the main thread anyway,
 * but the Compose test rule's dispatcher goes on in whichever thread resumes it and sends the snapshot's changes from
 * there. What they resume then runs beside the main thread, and Compose throws on the main thread ("Detected
 * multithreaded access to SnapshotStateObserver").
 */
internal suspend fun <T> inBackground(main: CoroutineDispatcher = Dispatchers.Main.immediate, block: () -> T): T = withContext(main) { withContext(Dispatchers.IO) { block() } }
