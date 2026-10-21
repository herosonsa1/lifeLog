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
import com.autologue.app.presentation.common.EasyModalModeSwitchChip
import com.autologue.app.presentation.common.EasyTheme
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.LocalEasyMode
import com.autologue.app.presentation.theme.*

@Composable
fun VehicleManageDialog(
    vehicles: List<VehicleProfile>,
    onDismiss: () -> Unit,
    onSaveVehicles: (car1: VehicleProfile, car2: VehicleProfile) -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    val car1Initial = vehicles.find { it.id == "car_1" } ?: VehicleProfile("car_1", "차량 1 (메인)")
    val car2Initial = vehicles.find { it.id == "car_2" } ?: VehicleProfile("car_2", "차량 2 (서브)", fuelType = "하이브리드")

    var car1Name by remember { mutableStateOf(car1Initial.name) }
    var car1Plate by remember { mutableStateOf(car1Initial.licensePlate) }
    var car1Fuel by remember { mutableStateOf(car1Initial.fuelType) }
    var car1Bt by remember { mutableStateOf(car1Initial.bluetoothDevice) }
    var car1Efficiency by remember { mutableStateOf(car1Initial.targetEfficiencyKmPerL.toString()) }
    var car1DefaultPrice by remember { mutableStateOf(car1Initial.defaultGasPrice.toInt().toString()) }

    var car2Name by remember { mutableStateOf(car2Initial.name) }
    var car2Plate by remember { mutableStateOf(car2Initial.licensePlate) }
    var car2Fuel by remember { mutableStateOf(car2Initial.fuelType) }
    var car2Bt by remember { mutableStateOf(car2Initial.bluetoothDevice) }
    var car2Efficiency by remember { mutableStateOf(car2Initial.targetEfficiencyKmPerL.toString()) }
    var car2DefaultPrice by remember { mutableStateOf(car2Initial.defaultGasPrice.toInt().toString()) }

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
                shape = RoundedCornerShape(if (isEasyMode) 28.dp else 20.dp),
                color = if (isEasyMode) EasyTheme.bg else AppColors.surface,
                shadowElevation = if (isEasyMode) 0.dp else 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isEasyMode) 20.dp else Spacing.lg)
                ) {
                    // Dialog Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (!isEasyMode) {
                                Icon(
                                    Icons.Default.DirectionsCar,
                                    contentDescription = null,
                                    tint = MenuColors.carLedger,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = "보유 차량 프로필 관리 (2대)",
                                style = if (isEasyMode) {
                                    AppTypography.h2.copy(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = EasyTheme.textPrimary
                                    )
                                } else {
                                    AppTypography.h2
                                }
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            EasyModalModeSwitchChip()
                            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "닫기",
                                    tint = if (isEasyMode) EasyTheme.textSecondary else Slate400
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = if (isEasyMode) {
                            "차량별 애칭과 블루투스를 등록해 두시면, 탑승 시 자동으로 해당 차량의 주행/소모품에 연결됩니다."
                        } else {
                            "💡 차량별 애칭과 블루투스를 등록해 두시면, 탑승 시 자동으로 해당 차량의 주행/소모품에 연결됩니다."
                        },
                        style = AppTypography.captionMuted.copy(
                            fontSize = 11.5.sp,
                            color = if (isEasyMode) EasyTheme.textSecondary else AppColors.textMuted
                        )
                    )
                    Spacer(modifier = Modifier.height(if (isEasyMode) 12.dp else Spacing.xs))
                    if (!isEasyMode) {
                        HairlineDivider()
                        Spacer(modifier = Modifier.height(Spacing.xs))
                    }

                    // Scrollable Body
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 14.dp else Spacing.md)
                    ) {
                        val car1Emoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(car1Name)
                        val car1Desc = com.autologue.app.util.VehicleBrandUtils.getBrandEmblemDescription(car1Name)
                        val car2Emoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(car2Name)
                        val car2Desc = com.autologue.app.util.VehicleBrandUtils.getBrandEmblemDescription(car2Name)

                        // Vehicle 1 Card
                        VehicleEditCard(
                            cardTitle = if (isEasyMode) "차량 1 (주 차량/출퇴근) · $car1Desc" else "$car1Emoji 차량 1 (주 차량/출퇴근) · $car1Desc",
                            name = car1Name,
                            onNameChange = { car1Name = it },
                            plate = car1Plate,
                            onPlateChange = { car1Plate = it },
                            fuelType = car1Fuel,
                            onFuelTypeChange = { car1Fuel = it },
                            bluetooth = car1Bt,
                            onBluetoothChange = { car1Bt = it },
                            efficiency = car1Efficiency,
                            onEfficiencyChange = { car1Efficiency = it },
                            defaultGasPrice = car1DefaultPrice,
                            onDefaultGasPriceChange = { car1DefaultPrice = it }
                        )

                        // Vehicle 2 Card
                        VehicleEditCard(
                            cardTitle = if (isEasyMode) "차량 2 (보조 차량/세컨카) · $car2Desc" else "$car2Emoji 차량 2 (보조 차량/세컨카) · $car2Desc",
                            name = car2Name,
                            onNameChange = { car2Name = it },
                            plate = car2Plate,
                            onPlateChange = { car2Plate = it },
                            fuelType = car2Fuel,
                            onFuelTypeChange = { car2Fuel = it },
                            bluetooth = car2Bt,
                            onBluetoothChange = { car2Bt = it },
                            efficiency = car2Efficiency,
                            onEfficiencyChange = { car2Efficiency = it },
                            defaultGasPrice = car2DefaultPrice,
                            onDefaultGasPriceChange = { car2DefaultPrice = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(if (isEasyMode) 10.dp else Spacing.xs))
                    if (!isEasyMode) {
                        HairlineDivider()
                    }
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
                                val price1 = car1DefaultPrice.toDoubleOrNull() ?: 1650.0
                                val price2 = car2DefaultPrice.toDoubleOrNull() ?: 1650.0

                                val updatedCar1 = car1Initial.copy(
                                    name = car1Name.trim().ifBlank { "차량 1" },
                                    licensePlate = car1Plate.trim(),
                                    fuelType = car1Fuel,
                                    bluetoothDevice = car1Bt.trim(),
                                    targetEfficiencyKmPerL = eff1,
                                    defaultGasPrice = price1
                                )
                                val updatedCar2 = car2Initial.copy(
                                    name = car2Name.trim().ifBlank { "차량 2" },
                                    licensePlate = car2Plate.trim(),
                                    fuelType = car2Fuel,
                                    bluetoothDevice = car2Bt.trim(),
                                    targetEfficiencyKmPerL = eff2,
                                    defaultGasPrice = price2
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
    onEfficiencyChange: (String) -> Unit,
    defaultGasPrice: String,
    onDefaultGasPriceChange: (String) -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    val fuelOptions = listOf("가솔린", "디젤", "하이브리드", "전기차", "LPG")

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = if (isEasyMode) EasyTheme.surfaceSubtle else AppColors.surface,
        unfocusedContainerColor = if (isEasyMode) EasyTheme.surfaceSubtle else AppColors.surface,
        focusedBorderColor = if (isEasyMode) EasyTheme.blue else MenuColors.carLedger,
        unfocusedBorderColor = if (isEasyMode) Color.Transparent else Slate300
    )
    val textFieldShape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp)

    Surface(
        shape = RoundedCornerShape(if (isEasyMode) 20.dp else 12.dp),
        color = if (isEasyMode) EasyTheme.surface else Slate50,
        border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(if (isEasyMode) 16.dp else 12.dp)) {
            Text(
                text = cardTitle,
                style = AppTypography.h3.copy(
                    color = if (isEasyMode) EasyTheme.textPrimary else MenuColors.carLedger,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isEasyMode) 15.sp else 14.sp
                )
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Row 1: 차량명 & 차량번호
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1.3f)) {
                    Text(
                        "차량 이름/애칭",
                        style = AppTypography.caption.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEasyMode) EasyTheme.textSecondary else Slate700
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChange,
                        placeholder = { Text("예: 제네시스 GV80", fontSize = 12.sp, color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = textFieldShape,
                        textStyle = AppTypography.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                        colors = textFieldColors
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "차량 번호 (선택)",
                        style = AppTypography.caption.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEasyMode) EasyTheme.textSecondary else Slate700
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = plate,
                        onValueChange = onPlateChange,
                        placeholder = { Text("예: 12가3456", fontSize = 12.sp, color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = textFieldShape,
                        textStyle = AppTypography.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                        colors = textFieldColors
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 2: 유종 선택 칩
            Text(
                "유종 선택",
                style = AppTypography.caption.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (isEasyMode) EasyTheme.textSecondary else Slate700
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fuelOptions.forEach { fOption ->
                    val isFuelSelected = fuelType == fOption
                    Surface(
                        onClick = { onFuelTypeChange(fOption) },
                        shape = RoundedCornerShape(if (isEasyMode) 8.dp else 6.dp),
                        color = if (isFuelSelected) {
                            if (isEasyMode) EasyTheme.blue else MenuColors.carLedgerBg
                        } else {
                            if (isEasyMode) EasyTheme.surfaceSubtle else Slate200
                        },
                        border = if (isEasyMode) null else BorderStroke(
                            1.dp,
                            if (isFuelSelected) MenuColors.carLedgerBorder else Color.Transparent
                        )
                    ) {
                        Text(
                            text = fOption,
                            fontSize = 11.sp,
                            fontWeight = if (isFuelSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isFuelSelected) {
                                if (isEasyMode) PureWhite else MenuColors.carLedger
                            } else {
                                if (isEasyMode) EasyTheme.textSecondary else Slate700
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 3: 블루투스 기기명 & 목표 연비
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1.3f)) {
                    Text(
                        "블루투스 이름 (자동 감지)",
                        style = AppTypography.caption.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEasyMode) EasyTheme.textSecondary else Slate700
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = bluetooth,
                        onValueChange = onBluetoothChange,
                        placeholder = { Text("예: Genesis, K5 등", fontSize = 12.sp, color = Slate400) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = textFieldShape,
                        textStyle = AppTypography.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                        colors = textFieldColors
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "공인/목표 연비",
                        style = AppTypography.caption.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEasyMode) EasyTheme.textSecondary else Slate700
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = efficiency,
                        onValueChange = onEfficiencyChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = textFieldShape,
                        textStyle = AppTypography.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                        trailingIcon = {
                            Text(
                                "km/L",
                                fontSize = 10.sp,
                                color = if (isEasyMode) EasyTheme.textMuted else Slate400,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        },
                        colors = textFieldColors
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 4: 기본 유가 (미입력 시 적용 단가)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "기본 주유 유가 (미입력 시 적용)",
                        style = AppTypography.caption.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEasyMode) EasyTheme.textSecondary else Slate700
                        )
                    )
                    Text(
                        "주유량/단가 미입력 결제 시 적용",
                        fontSize = 10.5.sp,
                        color = if (isEasyMode) EasyTheme.textMuted else Slate500
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = defaultGasPrice,
                    onValueChange = onDefaultGasPriceChange,
                    placeholder = { Text("1650", fontSize = 12.sp, color = Slate400) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = textFieldShape,
                    textStyle = AppTypography.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                    trailingIcon = {
                        Text(
                            "원/L",
                            fontSize = 10.sp,
                            color = if (isEasyMode) EasyTheme.textMuted else Slate400,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    },
                    colors = textFieldColors
                )
            }
        }
    }
}
