package com.autologue.app.presentation.common

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 결제 내역 상세 설정의 '메모 작성' 클릭 시 전체 메시지 자동 선택 및 즉시 덮어쓰기 입력 동작 검증 테스트
 */
class SelectAllOnTapTextFieldTest {

    @Test
    fun `기존 메모 클릭 시 전체 문자열(0_length)이 모두 선택되고 조합 상태가 초기화된다`() {
        val existingMemo = "출금 내역 (계좌: 312-****-9414-21, 잔액 383,252원)"
        val selected = createSelectAllTextFieldValue(existingMemo)

        assertThat(selected.text).isEqualTo(existingMemo)
        assertThat(selected.selection).isEqualTo(TextRange(0, existingMemo.length))
        assertThat(selected.composition).isNull()
    }

    @Test
    fun `탭 직후 CoreTextField가 단일 커서 오프셋으로 덮어쓰려 해도 가드 활성 중에는 전체 선택을 유지한다`() {
        val existingMemo = "출금 내역 (계좌: 312-****-9414-21, 잔액 383,252원)"
        val previous = createSelectAllTextFieldValue(existingMemo)
        // CoreTextField의 탭 오프셋 처리기가 커서를 중간(12)으로 이동시키려는 콜백 시뮬레이션
        val incomingTapCollapse = TextFieldValue(
            text = existingMemo,
            selection = TextRange(12, 12),
            composition = null
        )

        val (resolved, keepGuard) = resolveSelectAllOnTapValueChange(
            previousValue = previous,
            incomingValue = incomingTapCollapse,
            isSelectAllGuardActive = true
        )

        assertThat(resolved.text).isEqualTo(existingMemo)
        assertThat(resolved.selection).isEqualTo(TextRange(0, existingMemo.length))
        assertThat(keepGuard).isTrue()
    }

    @Test
    fun `전체 선택 상태에서 새 한글 자모를 입력하면 즉시 기존 전체 메시지가 대체되고 가드가 해제된다`() {
        val existingMemo = "출금 내역 (계좌: 312-****-9414-21, 잔액 383,252원)"
        val previous = createSelectAllTextFieldValue(existingMemo)

        // 첫 한글 자모 'ㅎ' 입력으로 전체 선택 구간이 대체된 상태
        val incomingFirstJamo = TextFieldValue(
            text = "ㅎ",
            selection = TextRange(1, 1),
            composition = TextRange(0, 1)
        )

        val (resolvedFirst, keepGuardAfterFirst) = resolveSelectAllOnTapValueChange(
            previousValue = previous,
            incomingValue = incomingFirstJamo,
            isSelectAllGuardActive = true
        )

        assertThat(resolvedFirst.text).isEqualTo("ㅎ")
        assertThat(resolvedFirst.selection).isEqualTo(TextRange(1, 1))
        assertThat(resolvedFirst.composition).isEqualTo(TextRange(0, 1))
        assertThat(keepGuardAfterFirst).isFalse()

        // 이어서 '회식' 조합 입력 시 정상적으로 커서와 조합 범위가 유지된다
        val incomingSecond = TextFieldValue(
            text = "회식",
            selection = TextRange(2, 2),
            composition = TextRange(1, 2)
        )
        val (resolvedSecond, keepGuardAfterSecond) = resolveSelectAllOnTapValueChange(
            previousValue = resolvedFirst,
            incomingValue = incomingSecond,
            isSelectAllGuardActive = keepGuardAfterFirst
        )

        assertThat(resolvedSecond.text).isEqualTo("회식")
        assertThat(resolvedSecond.selection).isEqualTo(TextRange(2, 2))
        assertThat(resolvedSecond.composition).isEqualTo(TextRange(1, 2))
        assertThat(keepGuardAfterSecond).isFalse()
    }

    @Test
    fun `이미 포커스된 상태에서 메모 입력란을 다시 클릭해도 전체 문구가 다시 모두 선택된다`() {
        val editedMemo = "팀 점심 회식"
        val reselected = createSelectAllTextFieldValue(editedMemo)
        val incomingTapCollapse = TextFieldValue(
            text = editedMemo,
            selection = TextRange(3, 3),
            composition = null
        )

        val (resolved, keepGuard) = resolveSelectAllOnTapValueChange(
            previousValue = reselected,
            incomingValue = incomingTapCollapse,
            isSelectAllGuardActive = true
        )

        assertThat(resolved.selection).isEqualTo(TextRange(0, editedMemo.length))
        assertThat(keepGuard).isTrue()
    }

    @Test
    fun `빈 메모 문자열 클릭 시 인덱스 오류 없이 0_0 범위를 유지한다`() {
        val emptySelected = createSelectAllTextFieldValue("")
        assertThat(emptySelected.text).isEmpty()
        assertThat(emptySelected.selection).isEqualTo(TextRange(0, 0))
    }
}
