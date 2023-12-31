package com.personio.reminders.infra.postgres

import com.personio.reminders.domain.occurrences.Occurrence
import com.personio.reminders.domain.occurrences.OccurrencesRepository
import com.personio.reminders.domain.reminders.Reminder
import com.personio.reminders.util.addToInstant
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.generated.tables.Occurrences.OCCURRENCES
import org.jooq.generated.tables.Reminders.REMINDERS
import org.jooq.generated.tables.records.OccurrencesRecord
import org.jooq.generated.tables.records.RemindersRecord
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository

private const val NOT_ACKNOWLEDGED = false
private const val LAST_OCCURRENCE_TIMESTAMP = "LAST_OCCURRENCE_TIMESTAMP"

/**
 * This is the default implementation of the reminder's occurrences repository.
 * This class implements a repository which integrates with the Postgres database.
 */
@Repository
class PostgresOccurrencesRepository(
    /**
     * The following properties are injected by Spring's Dependency Injection container,
     * during the instantiation of this controller.
     *
     * The `DSLContext` property is the JOOQ's (https://www.jooq.org/) abstraction that interacts with the database.
     */
    private val dslContext: DSLContext,
    private val clock: Clock
) : OccurrencesRepository {

    override fun create(reminderId: UUID, date: String): UUID {
        val id = UUID.randomUUID()

        /*
         * The `OCCURRENCES` constant, and its class, are auto-generated by JOOQ.
         *
         * JOOQ generates one class per table from your database,
         * i.e. a `occurrences` table will result in a `Occurrences` class in JOOQ.
         *
         * These classes are then used to create compiled queries,
         * like the ones you can check in this file.
         *
         * Check the README file to learn how to execute JOOQ's code generation task.
         */
        dslContext.insertInto(
            OCCURRENCES
        ).columns(
            OCCURRENCES.ID,
            OCCURRENCES.REMINDER_ID,
            OCCURRENCES.TIMESTAMP,
            OCCURRENCES.IS_ACKNOWLEDGED
        ).values(
            id,
            reminderId,
            Instant.parse(date),
            NOT_ACKNOWLEDGED
        ).execute()

        return id
    }

    /* Returns all unacknowledged occurrences before a given timestamp */
    override fun findAt(instant: Instant): Collection<Occurrence> {
        val records = dslContext.select()
            .from(OCCURRENCES)
            .join(REMINDERS).on(
                REMINDERS.ID.eq(OCCURRENCES.REMINDER_ID)
            )
            .where(
                OCCURRENCES.TIMESTAMP.lt(instant)
            )
            .fetchGroups {
                val reminder = it.into(REMINDERS).toReminder()
                it.into(OCCURRENCES).toReminderOccurrence(reminder)
            }
        return records.map { it.key }
    }

    /* Returns all unacknowledged occurrences before a given timestamp for a given employee */
    override fun findAt(instant: Instant, employeeId: UUID): Collection<Occurrence> {
        val records = dslContext.select()
            .from(OCCURRENCES)
            .join(REMINDERS).on(
                REMINDERS.ID.eq(OCCURRENCES.REMINDER_ID)
                    .and(REMINDERS.EMPLOYEE_ID.eq(employeeId))
            )
            .where(OCCURRENCES.TIMESTAMP.lt(instant))
            .and(OCCURRENCES.IS_ACKNOWLEDGED.isFalse)
            .fetchGroups {
                val reminder = it.into(REMINDERS).toReminder()
                it.into(OCCURRENCES).toReminderOccurrence(reminder)
            }

        return records.map { it.key }
    }

    override fun findBy(id: UUID): Occurrence? {
        return dslContext.select()
            .from(OCCURRENCES)
            .join(REMINDERS).on(
                REMINDERS.ID.eq(OCCURRENCES.REMINDER_ID)
            )
            .where(OCCURRENCES.ID.eq(id))
            .fetchGroups {
                val reminder = it.into(REMINDERS).toReminder()
                it.into(OCCURRENCES).toReminderOccurrence(reminder)
            }.map { it.key }.firstOrNull()
    }

    override fun getInstantForNextReminderOccurrences(): Map<UUID, Instant> {
        val remindersLastOccurrences = dslContext.select(
            REMINDERS.ID,
            REMINDERS.TIMESTAMP,
            REMINDERS.RECURRENCE_INTERVAL,
            REMINDERS.RECURRENCE_FREQUENCY,
            DSL.max(OCCURRENCES.TIMESTAMP).`as`(LAST_OCCURRENCE_TIMESTAMP)
        )
            .from(REMINDERS)
            .leftJoin(OCCURRENCES).on(OCCURRENCES.REMINDER_ID.eq(REMINDERS.ID))
            .where(
                REMINDERS.RECURRENCE_INTERVAL.isNotNull
                    .and(REMINDERS.RECURRENCE_FREQUENCY.isNotNull)
            ).groupBy(
                REMINDERS.ID,
                REMINDERS.TIMESTAMP,
                REMINDERS.RECURRENCE_INTERVAL,
                REMINDERS.RECURRENCE_FREQUENCY
            ).fetch()

        val nextRemindersInstants = remindersLastOccurrences
            .filter {
                val lastOccurrenceTimestamp = it[LAST_OCCURRENCE_TIMESTAMP] as Instant?
                lastOccurrenceTimestamp == null || lastOccurrenceTimestamp <= Instant.now(clock)
            }.map {
                val lastOccurrenceTimestamp = it[LAST_OCCURRENCE_TIMESTAMP] as Instant?
                val instantOfNextOccurrence = if (lastOccurrenceTimestamp == null) {
                    it[REMINDERS.TIMESTAMP]
                } else {
                    val unit = convertFrequencyToChronoUnit(it[REMINDERS.RECURRENCE_FREQUENCY])
                    val interval = it[REMINDERS.RECURRENCE_INTERVAL].toLong()
                    unit.addToInstant(lastOccurrenceTimestamp, interval, clock)
                }

                it[REMINDERS.ID] to instantOfNextOccurrence
            }

        return nextRemindersInstants.toMap()
    }

    override fun markAsNotified(occurrence: Occurrence) {
        dslContext.update(OCCURRENCES)
            .set(OCCURRENCES.NOTIFICATION_SENT, true)
            .where(OCCURRENCES.ID.eq(occurrence.id))
            .execute()
    }

    override fun acknowledge(occurrence: Occurrence) {
        dslContext.update(OCCURRENCES)
            .set(OCCURRENCES.IS_ACKNOWLEDGED, true)
            .where(OCCURRENCES.ID.eq(occurrence.id))
            .execute()
    }

    private fun OccurrencesRecord.toReminderOccurrence(reminder: Reminder): Occurrence {
        return Occurrence(
            id = this.id,
            reminder = reminder,
            isNotificationSent = this.notificationSent,
            date = this.timestamp.toString(),
            isAcknowledged = this.isAcknowledged
        )
    }

    private fun RemindersRecord.toReminder(): Reminder {
        return Reminder(
            id = this.id,
            employeeId = this.employeeId,
            text = this.text,
            date = this.timestamp.toString(),
            isRecurring = this.isRecurring,
            recurringInterval = this.recurrenceInterval,
            recurringFrequency = this.recurrenceFrequency
        )
    }

    private fun convertFrequencyToChronoUnit(frequency: Int): ChronoUnit {
        return when (frequency) {
            1 -> ChronoUnit.DAYS
            2 -> ChronoUnit.WEEKS
            3 -> ChronoUnit.MONTHS
            4 -> ChronoUnit.YEARS
            else -> throw IllegalArgumentException("Invalid frequency provided")
        }
    }
}
