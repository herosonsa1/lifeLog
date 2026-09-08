package com.autologue.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.autologue.app.data.parser.SmsParser
import com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmsBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var processTransactionUseCase: ProcessTransactionUseCase

    // [H-04] 외부 정적 Scope 제거 — goAsync() 기반 패턴으로 전환
    //   기존: private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    //   → onReceive() 반환 후 시스템 프로세스 종료 가능 → DB 기록 유실 위험

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
                val fullBody = messages.joinToString("") { it.messageBody ?: "" }
                val sender = messages.firstOrNull()?.originatingAddress

                val parsedTx = SmsParser.parse(sender, fullBody) ?: return

                // [H-04] goAsync(): onReceive() 반환 후에도 프로세스를 살려 DB 기록 완료를 보장
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        runCatching { processTransactionUseCase(parsedTx) }
                    } finally {
                        // 코루틴 완료 후 PendingResult.finish() → 시스템에 작업 완료 신호
                        pendingResult.finish()
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("SmsBroadcastReceiver", "SMS 수신 처리 중 예외 안전 포획", t)
        }
    }
}
