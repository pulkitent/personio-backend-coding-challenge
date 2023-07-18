package com.personio.reminders.api.http.v1.requests

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID
import org.jetbrains.annotations.NotNull

data class CreateReminderRequest(
    @NotNull
    val text: String,
    @JsonProperty("employee_id")
    val employeeId: UUID,
    val date: String,
    @JsonProperty("is_recurring")
    val isRecurring: Boolean,
    @JsonProperty("recurrence_interval")
    val recurrenceInterval: Int?,
    @JsonProperty("recurrence_frequency")
    val recurrenceFrequency: Int?
)
