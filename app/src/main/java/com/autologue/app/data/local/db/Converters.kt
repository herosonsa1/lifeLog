package com.autologue.app.data.local.db

import androidx.room.TypeConverter
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.VehicleLogType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): LocalDateTime? {
        return value?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }
    }

    @TypeConverter
    fun dateToTimestamp(date: LocalDateTime?): Long? {
        return date?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
    }

    @TypeConverter
    fun fromStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split("|||")
    }

    @TypeConverter
    fun toStringList(list: List<String>?): String {
        return list?.joinToString("|||") ?: ""
    }

    @TypeConverter
    fun fromIntList(value: String?): List<Int> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    @TypeConverter
    fun toIntList(list: List<Int>?): String {
        return list?.joinToString(",") ?: ""
    }

    @TypeConverter
    fun fromDoubleList(value: String?): List<Double> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    }

    @TypeConverter
    fun toDoubleList(list: List<Double>?): String {
        return list?.joinToString(",") ?: ""
    }

    @TypeConverter
    fun fromPaymentMethod(value: String?): PaymentMethod = value?.let { runCatching { enumValueOf<PaymentMethod>(it) }.getOrDefault(PaymentMethod.UNKNOWN) } ?: PaymentMethod.UNKNOWN

    @TypeConverter
    fun toPaymentMethod(method: PaymentMethod?): String = method?.name ?: PaymentMethod.UNKNOWN.name

    @TypeConverter
    fun fromExpenseCategory(value: String?): ExpenseCategory = value?.let { runCatching { enumValueOf<ExpenseCategory>(it) }.getOrDefault(ExpenseCategory.ETC) } ?: ExpenseCategory.ETC

    @TypeConverter
    fun toExpenseCategory(category: ExpenseCategory?): String = category?.name ?: ExpenseCategory.ETC.name

    @TypeConverter
    fun fromVehicleLogType(value: String?): VehicleLogType = value?.let { runCatching { enumValueOf<VehicleLogType>(it) }.getOrDefault(VehicleLogType.TRIP_DRIVING) } ?: VehicleLogType.TRIP_DRIVING

    @TypeConverter
    fun toVehicleLogType(type: VehicleLogType?): String = type?.name ?: VehicleLogType.TRIP_DRIVING.name

    @TypeConverter
    fun fromGolfType(value: String?): GolfType = value?.let { runCatching { enumValueOf<GolfType>(it) }.getOrDefault(GolfType.FIELD) } ?: GolfType.FIELD

    @TypeConverter
    fun toGolfType(type: GolfType?): String = type?.name ?: GolfType.FIELD.name

    @TypeConverter
    fun fromRouteStepList(value: String?): List<com.autologue.app.domain.model.RouteStep> {
        if (value.isNullOrEmpty()) return emptyList()
        val list = mutableListOf<com.autologue.app.domain.model.RouteStep>()
        try {
            val arr = org.json.JSONArray(value)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val timeMillis = obj.optLong("timeMillis", 0L)
                val dt = if (timeMillis > 0) {
                    Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
                } else {
                    LocalDateTime.now()
                }
                val stepTypeStr = obj.optString("stepType", "PHOTO")
                val stepType = runCatching {
                    com.autologue.app.domain.model.RouteStepType.valueOf(stepTypeStr)
                }.getOrDefault(com.autologue.app.domain.model.RouteStepType.PHOTO)

                val photoArr = obj.optJSONArray("photoUris")
                val photoUris = mutableListOf<String>()
                if (photoArr != null) {
                    for (j in 0 until photoArr.length()) {
                        photoUris.add(photoArr.getString(j))
                    }
                }

                val compArr = obj.optJSONArray("companions")
                val companions = mutableListOf<String>()
                if (compArr != null) {
                    for (j in 0 until compArr.length()) {
                        companions.add(compArr.getString(j))
                    }
                }

                val tagArr = obj.optJSONArray("tags")
                val tags = mutableListOf<String>()
                if (tagArr != null) {
                    for (j in 0 until tagArr.length()) {
                        tags.add(tagArr.getString(j))
                    }
                }

                list.add(
                    com.autologue.app.domain.model.RouteStep(
                        id = obj.optString("id", ""),
                        time = dt,
                        stepType = stepType,
                        title = obj.optString("title", ""),
                        description = obj.optString("description", "").takeIf { it.isNotBlank() },
                        locationName = obj.optString("locationName", "").takeIf { it.isNotBlank() },
                        address = obj.optString("address", "").takeIf { it.isNotBlank() },
                        latitude = if (obj.has("latitude") && !obj.isNull("latitude")) obj.optDouble("latitude") else null,
                        longitude = if (obj.has("longitude") && !obj.isNull("longitude")) obj.optDouble("longitude") else null,
                        amount = if (obj.has("amount") && !obj.isNull("amount")) obj.optLong("amount") else null,
                        photoUris = photoUris,
                        category = obj.optString("category", "").takeIf { it.isNotBlank() },
                        companions = companions,
                        tags = tags
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    @TypeConverter
    fun toRouteStepList(list: List<com.autologue.app.domain.model.RouteStep>?): String {
        if (list.isNullOrEmpty()) return ""
        val arr = org.json.JSONArray()
        for (step in list) {
            val obj = org.json.JSONObject().apply {
                put("id", step.id)
                put("timeMillis", step.time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                put("stepType", step.stepType.name)
                put("title", step.title)
                put("description", step.description ?: "")
                put("locationName", step.locationName ?: "")
                put("address", step.address ?: "")
                step.latitude?.let { put("latitude", it) }
                step.longitude?.let { put("longitude", it) }
                step.amount?.let { put("amount", it) }
                val photoArr = org.json.JSONArray()
                step.photoUris.forEach { photoArr.put(it) }
                put("photoUris", photoArr)

                val compArr = org.json.JSONArray()
                step.companions.forEach { compArr.put(it) }
                put("companions", compArr)

                val tagArr = org.json.JSONArray()
                step.tags.forEach { tagArr.put(it) }
                put("tags", tagArr)

                put("category", step.category ?: "")
            }
            arr.put(obj)
        }
        return arr.toString()
    }
}
