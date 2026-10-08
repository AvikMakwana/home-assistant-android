package io.homeassistant.companion.android.qs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.BaseActivity
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.SdkVersion
import io.homeassistant.companion.android.database.qs.TileDao
import io.homeassistant.companion.android.database.qs.isSetup
import io.homeassistant.companion.android.frontend.navigation.FrontendTarget
import io.homeassistant.companion.android.launch.LaunchActivity
import io.homeassistant.companion.android.launch.intentLaunchWithNavigateTo
import io.homeassistant.companion.android.settings.SettingsActivity
import io.homeassistant.companion.android.settings.qs.tileSlots
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@AndroidEntryPoint
class TilePreferenceActivity : BaseActivity() {

    companion object {
        private const val EXTRA_ENTITY_ID = "entity_id"

        fun newInstance(context: Context, entityId: String): Intent =
            Intent(context, TilePreferenceActivity::class.java).apply {
                putExtra(EXTRA_ENTITY_ID, entityId)
            }
    }

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var tileDao: TileDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var tileId = "-1"
        if (SdkVersion.isAtLeast(Build.VERSION_CODES.O)) {
            intent.extras?.let { extras ->
                BundleCompat.getParcelable(
                    extras,
                    Intent.EXTRA_COMPONENT_NAME,
                    ComponentName::class.java,
                )?.let { component ->
                    try {
                        val tileClass = Class.forName(component.className)
                        tileSlots.firstOrNull { it.serviceClass == tileClass }?.id?.value?.let {
                            Timber.d("Tile ID for long press action: $it")
                            tileId = it
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Couldn't get tile ID for component $component")
                    }
                }
            }
        }

        lifecycleScope.launch {
            val entityId = intent.getStringExtra(EXTRA_ENTITY_ID)
            if (entityId != null) {
                val tilesById = tileDao.getAll().associateBy { it.tileId }
                val availableTileId = tileSlots
                    .firstOrNull { tilesById[it.id.value]?.isSetup != true }
                    ?.id
                    ?.value
                if (availableTileId == null) {
                    Timber.w("No tile slot available for Add To Tile")
                    finishWithoutAnimation()
                    return@launch
                }
                tileId = availableTileId
            }
            val tileData = tileDao.get(tileId)

            val intent = if (!serverManager.isRegistered()) {
                Intent(this@TilePreferenceActivity, LaunchActivity::class.java)
            } else if (tileData?.isSetup == true) {
                if (entityId != null) {
                    Timber.w("Selected Add To Tile slot became configured before opening settings")
                    finishWithoutAnimation()
                    return@launch
                }
                this@TilePreferenceActivity.intentLaunchWithNavigateTo(
                    target = FrontendTarget.EntityMoreInfo(tileData.entityId),
                    serverId = tileData.serverId,
                )
            } else {
                SettingsActivity.newInstance(
                    this@TilePreferenceActivity,
                    SettingsActivity.Deeplink.QSTile(tileId, entityId),
                )
            }

            withContext(Dispatchers.Main) {
                startActivity(intent)
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0) // Disable activity start/stop animation
            }
        }
    }

    private suspend fun finishWithoutAnimation() {
        withContext(Dispatchers.Main) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
