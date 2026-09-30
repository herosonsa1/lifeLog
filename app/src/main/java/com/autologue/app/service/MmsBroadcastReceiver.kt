package com.autologue.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 장문 MMS/LMS 결제 문자 실시간 수신 브로드캐스트 리시버
 * Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION 등을 감지하여
 * 수신 직후 1~2초 딜레이(OS의 MMS DB 기록 대기) 후 최신 결제 내역을 자동 스캔하여 가계부에 적재합니다.
 */
@AndroidEntryPoint
class MmsBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var importer: HistoricalDataImporter

    @Inject
    lateinit var processTransactionUseCase: ProcessTransactionUseCase

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == "android.provider.Telephony.WAP_PUSH_RECEIVED" ||
            action == "android.provider.Telephony.MMS_RECEIVED"
        ) {
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    // OS가 MMS 테이블(content://mms)에 데이터와 파트를 영속화할 시간을 1.5초 부여
                    delay(1500L)
                    val result = importer.scanHistoricalSmsDetailed(context, daysBack = 1, limit = 10)
                    for (tx in result.transactions) {
                        runCatching { processTransactionUseCase(tx) }
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("MmsBroadcastReceiver", "MMS 실시간 수신 처리 중 예외", t)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
