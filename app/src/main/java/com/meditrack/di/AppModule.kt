package com.meditrack.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.meditrack.data.local.MediTrackDatabase
import com.meditrack.data.local.dao.DoseLogDao
import com.meditrack.data.local.dao.HomeWidgetDao
import com.meditrack.data.local.dao.MedicationDao
import com.meditrack.data.local.dao.ReminderEventDao
import com.meditrack.data.local.dao.ReviewCycleDao
import com.meditrack.data.local.dao.RingClipDao
import com.meditrack.data.prefs.settingsDataStore
import com.meditrack.data.repository.WidgetUpdater
import com.meditrack.data.repository.WidgetUpdaterImpl
import com.meditrack.domain.reminder.ReminderHeartbeat
import com.meditrack.domain.reminder.ReminderHeartbeatImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Object graph for the whole app.
 *
 * The database is a singleton bound to the application context: every repository shares one
 * connection, which is what makes the Flow-based UI consistent (a write performed by a notification
 * action is immediately visible to the today screen, the widget and the history calendar).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MediTrackDatabase =
        MediTrackDatabase.getInstance(context)

    @Provides
    fun provideMedicationDao(db: MediTrackDatabase): MedicationDao = db.medicationDao()

    @Provides
    fun provideDoseLogDao(db: MediTrackDatabase): DoseLogDao = db.doseLogDao()

    @Provides
    fun provideHomeWidgetDao(db: MediTrackDatabase): HomeWidgetDao = db.homeWidgetDao()

    @Provides
    fun provideReminderEventDao(db: MediTrackDatabase): ReminderEventDao = db.reminderEventDao()

    @Provides
    fun provideReviewCycleDao(db: MediTrackDatabase): ReviewCycleDao = db.reviewCycleDao()

    @Provides
    fun provideRingClipDao(db: MediTrackDatabase): RingClipDao = db.ringClipDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}

/** Interface-to-implementation bindings, kept separate so the object module above stays readable. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindReminderHeartbeat(impl: ReminderHeartbeatImpl): ReminderHeartbeat

    @Binds
    @Singleton
    abstract fun bindWidgetUpdater(impl: WidgetUpdaterImpl): WidgetUpdater
}
