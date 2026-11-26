package com.squaretoast.pos

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.squaretoast.pos.data.AppDatabase
import com.squaretoast.pos.data.OrderRepository
import com.squaretoast.pos.log.ScrubbingTree
import com.squaretoast.pos.sync.CloudApi
import com.squaretoast.pos.sync.DeviceCredentialStore
import com.squaretoast.pos.sync.DeviceSession
import com.squaretoast.pos.sync.MenuSync
import com.squaretoast.pos.sync.OutboxDrainer
import com.squaretoast.pos.terminal.TerminalSession
import com.squaretoast.pos.terminal.TerminalTokenProvider
import com.stripe.stripeterminal.TerminalApplicationDelegate
import com.stripe.stripeterminal.taptopay.TapToPay
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import java.time.Clock
import javax.inject.Singleton

@HiltAndroidApp
class PosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (TapToPay.isInTapToPayProcess()) return // the SDK's own process: no app initialisation
        TerminalApplicationDelegate.onCreate(this)
        Timber.plant(ScrubbingTree())
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "pos.db").addMigrations(*AppDatabase.MIGRATIONS).build()

    @Provides @Singleton
    fun orders(db: AppDatabase): OrderRepository = OrderRepository(db, Clock.systemUTC())

    @Provides @Singleton
    fun credentials(@ApplicationContext context: Context): DeviceCredentialStore = DeviceCredentialStore(context)

    @Provides @Singleton
    fun session(credentials: DeviceCredentialStore): DeviceSession = DeviceSession(credentials::token, credentials::clear)

    @Provides @Singleton
    fun cloud(session: DeviceSession): CloudApi =
        CloudApi(BuildConfig.CLOUD_BASE_URL, HttpClient(OkHttp), session::token, onRejectedCredential = session::rejected)

    @Provides @Singleton
    fun menuSync(db: AppDatabase, cloud: CloudApi): MenuSync = MenuSync(db, cloud, Clock.systemUTC())

    @Provides
    fun drainer(db: AppDatabase, cloud: CloudApi): OutboxDrainer = OutboxDrainer(db, cloud)

    @Provides @Singleton
    fun terminal(@ApplicationContext context: Context, cloud: CloudApi): TerminalSession = TerminalSession(
        context as Application,
        TerminalTokenProvider(Dispatchers.IO) { cloud.connectionToken() },
        debuggable = BuildConfig.DEBUG,
    )
}
