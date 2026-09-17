package com.autologue.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 사용자가 등록한 은행 계좌 정보 모델
 */
data class UserAccount(
    val id: String = UUID.randomUUID().toString(),
    val bankName: String,               // 예: "NH농협", "KB국민", "신한", "우리", "하나", "카카오뱅크", "토스뱅크"
    val accountNumberPattern: String,   // 예: "312-****-9414-21", "9414", "07491612193855"
    val alias: String                   // 예: "생활비 통장", "급여 통장", "비상금", "적금"
) {
    /**
     * 계좌 뱃지에 노출할 예쁜 라벨 (예: "NH농협 · 생활비 통장", "KB국민 · 급여")
     */
    val badgeLabel: String
        get() = if (alias.isNotBlank()) "$bankName · $alias" else bankName
}

@Singleton
class UserAccountPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_account_prefs", Context.MODE_PRIVATE)

    private val _accounts = MutableStateFlow<List<UserAccount>>(emptyList())
    val accounts: StateFlow<List<UserAccount>> = _accounts.asStateFlow()

    init {
        _accounts.value = loadAccounts()
    }

    private fun loadAccounts(): List<UserAccount> {
        val rawJson = prefs.getString("user_accounts_json", null)
        if (rawJson.isNullOrBlank()) {
            // 초기 기본값: 사용자의 실제 거래 내역에 등장한 계좌를 기본 프리셋으로 제공
            val defaultList = listOf(
                UserAccount(
                    id = "acc_nh_1",
                    bankName = "NH농협",
                    accountNumberPattern = "312-****-9414-21",
                    alias = "생활비 통장"
                ),
                UserAccount(
                    id = "acc_kb_1",
                    bankName = "KB국민",
                    accountNumberPattern = "07491612193855",
                    alias = "급여 통장"
                )
            )
            saveAccountsInternal(defaultList)
            return defaultList
        }

        val list = mutableListOf<UserAccount>()
        runCatching {
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    UserAccount(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        bankName = obj.optString("bankName", ""),
                        accountNumberPattern = obj.optString("accountNumberPattern", ""),
                        alias = obj.optString("alias", "")
                    )
                )
            }
        }
        return list
    }

    private fun saveAccountsInternal(list: List<UserAccount>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("bankName", item.bankName)
            obj.put("accountNumberPattern", item.accountNumberPattern)
            obj.put("alias", item.alias)
            arr.put(obj)
        }
        prefs.edit().putString("user_accounts_json", arr.toString()).apply()
        _accounts.value = list
    }

    fun addAccount(bankName: String, accountNumberPattern: String, alias: String) {
        val current = _accounts.value.toMutableList()
        current.add(
            UserAccount(
                bankName = bankName.trim(),
                accountNumberPattern = accountNumberPattern.trim(),
                alias = alias.trim()
            )
        )
        saveAccountsInternal(current)
    }

    fun updateAccount(id: String, bankName: String, accountNumberPattern: String, alias: String) {
        val current = _accounts.value.map {
            if (it.id == id) it.copy(bankName = bankName.trim(), accountNumberPattern = accountNumberPattern.trim(), alias = alias.trim())
            else it
        }
        saveAccountsInternal(current)
    }

    fun deleteAccount(id: String) {
        val current = _accounts.value.filter { it.id != id }
        saveAccountsInternal(current)
    }

    /**
     * 트랜잭션의 텍스트(원문, 메모, 가맹점명, 은행명 등)와 매칭되는 등록 계좌 탐색
     */
    fun findMatchingAccount(
        originalText: String,
        transferMemo: String? = null,
        merchantName: String? = null,
        cardOrBankName: String? = null
    ): UserAccount? {
        val memoStr = transferMemo ?: ""
        val merchantStr = merchantName ?: ""
        val bankStr = cardOrBankName ?: ""
        val rawCombined = "$originalText $memoStr $merchantStr $bankStr"
        val combined = rawCombined.replace("-", "").replace("*", "").replace(" ", "")

        for (acc in _accounts.value) {
            val pat = acc.accountNumberPattern.trim()
            if (pat.isBlank()) continue

            // 1. 정확한 마스킹 패턴 일치 (예: 312-****-9414-21)
            if (rawCombined.contains(pat, ignoreCase = true)) {
                return acc
            }

            // 2. 하이픈/공백 제거 후 부분 일치 (최소 4자리 이상일 때)
            val cleanPat = pat.replace("-", "").replace("*", "").replace(" ", "")
            if (cleanPat.length >= 4 && combined.contains(cleanPat, ignoreCase = true)) {
                return acc
            }
        }
        return null
    }
}
