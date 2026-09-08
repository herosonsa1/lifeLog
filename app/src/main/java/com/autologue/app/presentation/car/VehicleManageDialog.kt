package com.autologue.app.presentation.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autologue.app.data.preferences.VehicleProfile
import com.autologue.app.presentation.common.AutoLoguePrimaryButton
import com.autologue.app.presentation.common.AutoLogueSecondaryButton
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.theme.*

@Composable
fun VehicleManageDialog(
    vehicles: List<VehicleProfile>,
    onDismiss: () -> Unit,
    onSaveVehicles: (car1: VehicleProfile, car2: VehicleProfile) -> Unit
) {
    val car1Initial = vehicles.find { it.id == "car_1" } ?: VehicleProfile("car_1", "차량 1 (메인)")
    val car2Initial = vehicles.find { it.id == "car_2" } ?: VehicleProfile("car_2", "차량 2 (서브)", fuelType = "하이브리드")

    var car1Name by remember { mutableStateOf(car1Initial.name) }
    var car1Plate by remember { mutableStateOf(car1Initial.licensePlate) }
    var car1Fuel by remember { mutableStateOf(car1Initial.fuelType) }
    var car1Bt by remember { mutableStateOf(car1Initial.bluetoothDevice) }
    var car1Efficiency by remember { mutableStateOf(car1Initial.targetEfficiencyKmPerL.toString()) }

    var car2Name by remember { mutableStateOf(car2Initial.name) }
    var car2Plate by remember { mutableStateOf(car2Initial.licensePlate) }
    var car2Fuel by remember { mutableStateOf(car2Initial.fuelType) }
    var car2Bt by remember { mutableStateOf(car2Initial.bluetoothDevice) }
    var car2Efficiency by remember { mutableStateOf(car2Initial.targetEfficiencyKmPerL.toString()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            decorFitsSystemWindows = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.90f),
                shape = RoundedCornerShape(20.dp),
                color = AppColors.surface,
                shadowElevation = 8.dp
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg)
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = MenuColors.carLedger,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "보유 차량 프로필 관리 (2대)",
                            style = AppTypography.h2
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "닫기", tint = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "💡 차량별 애칭과 블루투스를 등록해 두시면, 탑승 시 자동으로 해당 차량의 주행/소모품에 연결됩니다.",
                    style = AppTypography.captionMuted.copy(fontSize = 11.sp)
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                HairlineDivider()
                Spacer(modifier = Modifier.height(Spacing.xs))

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    val car1Emoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(car1Name)
                    val car1Desc = com.autologue.app.util.VehicleBrandUtils.getBrandEmblemDescription(car1Name)
                    val car2Emoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(car2Name)
                    val car2Desc = com.autologue.app.util.VehicleBrandUtils.getBrandEmblemDescription(car2Name)

                    // Vehicle 1 Card
                    VehicleEditCard(
                        cardTitle = "$car1Emoji 차량 1 (주 차량/출퇴근) · $car1Desc",
                        name = car1Name,
                        onNameChange = { car1Name = it },
                        plate = car1Plate,
                        onPlateChange = { car1Plate = it },
                        fuelType = car1Fuel,
                        onFuelTypeChange = { car1Fuel = it },
                        bluetooth = car1Bt,
                        onBluetoothChange = { car1Bt = it },
                        efficiency = car1Efficiency,
                        onEfficiencyChange = { car1Efficiency = it }
                    )

                    // Vehicle 2 Card
                    VehicleEditCard(
                        cardTitle = "$car2Emoji 차량 2 (보조 차량/세컨카) · $car2Desc",
                        name = car2Name,
                        onNameChange = { car2Name = it },
                        plate = car2Plate,
                        onPlateChange = { car2Plate = it },
                        fuelType = car2Fuel,
                        onFuelTypeChange = { car2Fuel = it },
                        bluetooth = car2Bt,
                        onBluetoothChange = { car2Bt = it },
                        efficiency = car2Efficiency,
                        onEfficiencyChange = { car2Efficiency = it }
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xs))
                HairlineDivider()
                Spacer(modifier = Modifier.height(Spacing.sm))

                // Bottom Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AutoLogueSecondaryButton(
                        text = "취소",
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    AutoLoguePrimaryButton(
                        text = "저장 및 적용",
                        onClick = {
                            val eff1 = car1Efficiency.toDoubleOrNull() ?: 12.5
                            val eff2 = car2Efficiency.toDoubleOrNull() ?: 16.0

                            val updatedCar1 = car1Initial.copy(
                                name = car1Name.trim().ifBlank { "차량 1" },
                                licensePlate = car1Plate.trim(),
                                fuelType = car1Fuel,
                                bluetoothDevice = car1Bt.trim(),
                                targetEfficiencyKmPerL = eff1
                            )
                            val updatedCar2 = car2Initial.copy(
                                name = car2Name.trim().ifBlank { "차량 2" },
                                licensePlate = car2Plate.trim(),
                                fuelType = car2Fuel,
                                bluetoothDevice = car2Bt.trim(),
                                targetEfficiencyKmPerL = eff2
                            )

                            onSaveVehicles(updatedCar1, updatedCar2)
                        }
                    )
                }
            }
        }
    }
}
}

@Composable
private fun VehicleEditCard(
    cardTitle: String,
    name: String,
    onNameChange: (String) -> Unit,
    plate: String,
    onPlateChange: (String) -> Unit,
    fuelType: String,
    onFuelTypeChange: (String) -> Unit,
    bluetooth: String,
    onBluetoothChange: (String) -> Unit,
    efficiency: String,
    onEfficiencyChange: (String) -> Unit
) {
    val fuelOptions = listOf("가솔린", "디젤", "하이브리드", "전기차", "LPG")

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Slate50,
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = cardTitle,
                style = AppTypography.h3.copy(color = MenuColors.carLedger, fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Row 1: 차량명 & 차량번호
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1.3f)) {
                    Text("차량 이름/애칭", style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChange,
                        placeholder = { Text("예: 제네시스 GV80", fontSize = 12.sp, color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = AppTypography.body.copy(fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppColors.surface,
                            unfocusedContainerColor = AppColors.surface,
                            focusedBorderColor = MenuColors.carLedger,
                            unfocusedBorderColor = Slate300
                        )
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text("차량 번호 (선택)", style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = plate,
                        onValueChange = onPlateChange,
                        placeholder = { Text("예: 12가3456", fontSize = 12.sp, color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = AppTypography.body.copy(fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppColors.surface,
                            unfocusedContainerColor = AppColors.surface,
                            focusedBorderColor = MenuColors.carLedger,
                            unfocusedBorderColor = Slate300
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: 유종 선택 칩
            Text("유종 선택", style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold))
            Spacer(modifier = Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                fuelOptions.forEach { fOption ->
                    val isFuelSelected = fuelType == fOption
                    Surface(
                        onClick = { onFuelTypeChange(fOption) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isFuelSelected) MenuColors.carLedgerBg else Slate200,
                        border = BorderStroke(1.dp, if (isFuelSelected) MenuColors.carLedgerBorder else Color.Transparent)
                    ) {
                        Text(
                            text = fOption,
                            fontSize = 10.sp,
                            fontWeight = if (isFuelSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isFuelSelected) MenuColors.carLedger else Slate700,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 3: 블루투스 기기명 & 목표 연비
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1.3f)) {
                    Text("블루투스 이름 (자동 감지)", style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = bluetooth,
                        onValueChange = onBluetoothChange,
                        placeholder = { Text("예: Genesis, K5 등", fontSize = 12.sp, color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = AppTypography.body.copy(fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppColors.surface,
                            unfocusedContainerColor = AppColors.surface,
                            focusedBorderColor = MenuColors.carLedger,
                            unfocusedBorderColor = Slate300
                        )
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text("공인/목표 연비", style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = efficiency,
                        onValueChange = onEfficiencyChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = AppTypography.body.copy(fontSize = 13.sp),
                        trailingIcon = { Text("km/L", fontSize = 10.sp, color = Slate400, modifier = Modifier.padding(end = 4.dp)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppColors.surface,
                            unfocusedContainerColor = AppColors.surface,
                            focusedBorderColor = MenuColors.carLedger,
                            unfocusedBorderColor = Slate300
                        )
                    )
                }
            }
        }
    }
}
