package com.autologue.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 외부 사진(PhotoPicker 임시 URI, MediaStore 등)을 앱 전용 내부 저장소에 안전하게 복사하여
 * 앱 프로세스 종료, 재부팅 후에도 영구적으로 접근 가능한 file:// URI로 변환/관리하는 유틸리티입니다.
 */
object PhotoStorageManager {
    private const val TAG = "PhotoStorageManager"

    /**
     * 외부 Uri(PhotoPicker의 content://media/picker/... 등)를 앱 전용 내부 저장소로 스트림 복사합니다.
     *
     * @param context Context
     * @param uri 원본 Uri
     * @param subDir 저장할 하위 디렉터리 이름 (예: "scorecards", "golf_photos", "locker_slips")
     * @param prefix 파일명 접두어 (예: "scorecard", "field", "slip")
     * @return 내부 영구 저장소 파일의 Uri (실패 시 원본 Uri 반환)
     */
    fun saveUriToInternalStorage(
        context: Context,
        uri: Uri,
        subDir: String = "photos",
        prefix: String = "img"
    ): Uri {
        val uriString = uri.toString()
        // 이미 앱 내부 저장소 경로이거나 file 스키마인 경우 중복 복사 방지
        if (uriString.startsWith("file://") && uriString.contains(context.filesDir.absolutePath)) {
            return uri
        }

        return try {
            val targetDir = File(context.filesDir, subDir).apply {
                if (!exists()) mkdirs()
            }
            val fileName = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
            val targetFile = File(targetDir, fileName)

            var copied = false
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    copied = true
                }
            }

            if (copied && targetFile.exists() && targetFile.length() > 0) {
                Log.d(TAG, "사진 내부 영구 저장소 복사 완료: $uri -> ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                Uri.fromFile(targetFile)
            } else {
                Log.w(TAG, "사진 복사 실패 또는 빈 파일: $uri")
                uri
            }
        } catch (e: Exception) {
            Log.e(TAG, "사진 내부 저장소 저장 중 예외 발생 ($uri)", e)
            uri
        }
    }

    /**
     * 문자열 URI 리스트를 내부 저장소로 일괄 복사하여 영구 URI 문자열 리스트로 반환합니다.
     */
    fun saveUriStringsToInternalStorage(
        context: Context,
        uriStrings: List<String>,
        subDir: String = "photos",
        prefix: String = "img"
    ): List<String> {
        return uriStrings.map { uriString ->
            runCatching {
                val uri = Uri.parse(uriString)
                saveUriToInternalStorage(context, uri, subDir, prefix).toString()
            }.getOrDefault(uriString)
        }
    }
}
