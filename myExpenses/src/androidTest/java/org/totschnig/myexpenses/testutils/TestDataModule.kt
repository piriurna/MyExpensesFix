package org.totschnig.myexpenses.testutils

import android.content.Context
import android.content.SharedPreferences
import dagger.Provides
import org.totschnig.myexpenses.MyApplication
import org.totschnig.myexpenses.di.DataModule
import org.totschnig.myexpenses.preference.PrefHandler
import org.totschnig.myexpenses.provider.DatabaseVersionPeekHelper
import org.totschnig.myexpenses.util.ResultUnit
import java.util.*

object TestDataModule: DataModule() {
    private val randomDataName: String = UUID.randomUUID().toString()

    override val databaseName: String = randomDataName

    override fun providePrefHandler(context: MyApplication, sharedPreferences: SharedPreferences): PrefHandler {
        return TestPrefHandler(context, sharedPreferences, randomDataName)
    }

    override fun provideSharedPreferences(application: MyApplication): SharedPreferences =
            application.getSharedPreferences(randomDataName, Context.MODE_PRIVATE)

    override fun providePeekHelper(prefHandler: PrefHandler): DatabaseVersionPeekHelper =
        DatabaseVersionPeekHelper { _, _ -> ResultUnit }
}