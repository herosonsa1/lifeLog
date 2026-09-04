package com.autologue.app.domain.usecase.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.autologue.app.data.export.XlsxExporter
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class ExportResult(
    val fileUri: Uri,
    val fileName: String,
    val totalEntries: Int
)

@Singleton
class ExportAllDataToExcelUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diaryRepository: DiaryRepository,
    private val transactionRepository: TransactionRepository,
    private val vehicleRepository: VehicleRepository,
    private val golfRepository: GolfRepository
) {

    suspend operator fun invoke(): ExportResult = withContext(Dispatchers.IO) {
        val diaryEntries = diaryRepository.getDiaryEntriesFlow().first()
        val transactions = transactionRepository.getAllTransactionsFlow().first()
        val vehicleLogs = vehicleRepository.getAllVehicleLogsFlow().first()
        val golfRounds = golfRepository.getAllGolfRoundsFlow().first()

        val timeStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        val fileName = "AutoLogue_Lifelog_${timeStamp}.xlsx"

        // 1. Write to internal cache for FileProvider sharing
        val cacheFile = File(context.cacheDir, fileName)
        FileOutputStream(cacheFile).use { fos ->
            XlsxExporter.exportToStream(
                diaryEntries = diaryEntries,
                transactions = transactions,
                vehicleLogs = vehicleLogs,
                golfRounds = golfRounds,
                outputStream = fos
            )
        }

        // 2. Save a permanent copy to Download folder via MediaStore
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        cacheFile.inputStream().use { it.copyTo(os) }
                    }
                }
            } else {
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dlFile = File(dlDir, fileName)
                cacheFile.copyTo(dlFile, overwrite = true)
            }
            Unit
        }

        // 3. Return shareable URI via FileProvider
        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheFile
        )

        ExportResult(
            fileUri = shareUri,
            fileName = fileName,
            totalEntries = diaryEntries.size + transactions.size + vehicleLogs.size + golfRounds.size
        )
    }
}
