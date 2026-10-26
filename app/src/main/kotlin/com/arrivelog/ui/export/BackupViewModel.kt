package com.arrivelog.ui.export

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arrivelog.background.DetectionCoordinator
import com.arrivelog.data.backup.BackupCodec
import com.arrivelog.data.backup.BackupManager
import com.arrivelog.data.backup.InvalidBackupException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

sealed interface BackupMessage {
    data class Saved(val days: Int) : BackupMessage
    data class Restored(val days: Int, val workplaces: Int) : BackupMessage
    data object Invalid : BackupMessage
    data object Failed : BackupMessage
}

/** A picked file waiting for "Replace?" confirmation. Holds only the text, never the Uri grant. */
data class PendingRestore(val text: String, val days: Int, val workplaces: Int)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: BackupManager,
    private val coordinator: DetectionCoordinator,
    private val clock: Clock,
) : ViewModel() {

    private val _message = MutableStateFlow<BackupMessage?>(null)
    val message: StateFlow<BackupMessage?> = _message.asStateFlow()

    private val _pending = MutableStateFlow<PendingRestore?>(null)
    val pending: StateFlow<PendingRestore?> = _pending.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun suggestedName(): String = "arrivelog-backup-${LocalDate.now(clock)}.json"

    /** The user picked where to save (Storage Access Framework, so Drive / Downloads both work). */
    fun saveTo(uri: Uri) = work {
        val text = backup.export(clock.instant())
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IOException("Cannot open $uri")
        }
        _message.value = BackupMessage.Saved(BackupCodec.decode(text).workDays.size)
    }

    /** Reads and checks the file first; nothing changes until the user confirms. */
    fun open(uri: Uri) = work {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IOException("Cannot open $uri")
        }
        val contents = BackupCodec.decode(text)
        _pending.value = PendingRestore(text, contents.workDays.size, contents.workplaces.size)
    }

    fun confirmRestore() {
        val p = _pending.value ?: return
        _pending.value = null
        work {
            val result = backup.restore(p.text)
            coordinator.sync("RESTORE", force = true)
            _message.value = BackupMessage.Restored(result.days, result.workplaces)
        }
    }

    fun cancelRestore() {
        _pending.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        _message.value = null
        try {
            block()
        } catch (_: InvalidBackupException) {
            _message.value = BackupMessage.Invalid
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // I/O, a revoked file grant, or a database error: report it, never crash the screen.
            _message.value = BackupMessage.Failed
        } finally {
            _busy.value = false
        }
    }
}
