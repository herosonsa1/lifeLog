package com.autologue.app.presentation.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.data.preferences.VehicleProfile
import com.autologue.app.presentation.theme.*

/**
 * 차계부 상단 다중 차량(2대 이상) 선택 및 전환 칩 바
 */
@Composable
fun VehicleSelectorBar(
    vehicles: List<VehicleProfile>,
    selectedVehicleId: String,
    onSelectVehicle: (String) -> Unit,
    onManageVehicles: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = AppColors.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 차량 선택 칩 목록
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                vehicles.forEach { vehicle ->
                    val isSelected = vehicle.id == selectedVehicleId
                    Surface(
                        onClick = { onSelectVehicle(vehicle.id) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MenuColors.carLedgerBg else Slate100,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MenuColors.carLedgerBorder else Color.Transparent
                        ),
                        shadowElevation = if (isSelected) 1.dp else 0.dp
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DirectionsCar,
                                contentDescription = null,
                                tint = if (isSelected) MenuColors.carLedger else Slate500,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = vehicle.name,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MenuColors.carLedger else Slate700
                            )
                            if (vehicle.fuelType.isNotBlank()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(3.dp),
                                    color = if (isSelected) MenuColors.carLedger.copy(alpha = 0.15f) else Slate200
                                ) {
                                    Text(
                                        text = vehicle.fuelType,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) MenuColors.carLedger else Slate600,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 차량 관리 버튼
            Surface(
                onClick = onManageVehicles,
                shape = RoundedCornerShape(8.dp),
                color = Slate50,
                border = BorderStroke(1.dp, Slate200)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "차량 관리",
                        tint = Slate600,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "차량 관리",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate700
                    )
                }
            }
        }
    }
}
