package io.homeassistant.companion.android.frontend.addto

import android.content.Context
import android.os.Build
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.IntegrationDomains.CAMERA_DOMAIN
import io.homeassistant.companion.android.common.data.integration.IntegrationDomains.IMAGE_DOMAIN
import io.homeassistant.companion.android.common.data.integration.IntegrationDomains.MEDIA_PLAYER_DOMAIN
import io.homeassistant.companion.android.common.data.integration.IntegrationDomains.TODO_DOMAIN
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.prefs.AutoFavorite
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.FailFast
import io.homeassistant.companion.android.common.util.SdkVersion
import io.homeassistant.companion.android.database.qs.TileDao
import io.homeassistant.companion.android.database.qs.TileEntity
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.frontend.navigation.FrontendEvent
import io.homeassistant.companion.android.frontend.navigation.WidgetType
import io.homeassistant.companion.android.settings.qs.tileSlots
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

@ExperimentalCoroutinesApi
class FrontendEntityAddToManagerTest {

    private lateinit var serverManager: ServerManager
    private lateinit var prefsRepository: PrefsRepository
    private lateinit var integrationRepository: IntegrationRepository
    private lateinit var tileDao: TileDao
    private lateinit var context: Context

    private val serverId = 42

    @BeforeEach
    fun setUp() {
        serverManager = mockk()
        prefsRepository = mockk()
        integrationRepository = mockk()
        tileDao = mockk()
        context = mockk(relaxed = true)
        val server = mockk<Server>()

        every { server.id } returns serverId
        coEvery { serverManager.integrationRepository(serverId) } returns integrationRepository
        coEvery { serverManager.getServer() } returns server
        coEvery { tileDao.getAll() } returns configuredTilesForAllSlots()
        SdkVersion.sdkInt = Build.VERSION_CODES.N

        // Always override the handler so individual tests can decide to override it or not without impacting the others.
        FailFast.setHandler { exception, _ ->
            fail("Unhandled exception caught", exception)
        }
    }

    @AfterEach
    fun tearDown() {
        SdkVersion.resetSdkInt()
    }

    @Test
    fun `Given AndroidAutoFavorite action when executing then it adds favorite and returns ShowSnackbar`() = runTest {
        val entityId = "vehicle.test"
        coJustRun { prefsRepository.addAutoFavorite(any()) }

        val event = createManager().execute(entityId, EntityAddToAction.AndroidAutoFavorite)

        coVerify { prefsRepository.addAutoFavorite(AutoFavorite(serverId, entityId)) }
        assertEquals(FrontendEvent.ShowSnackbar(commonR.string.add_to_android_auto_success), event)
    }

    @Test
    fun `Given null server when adding AndroidAutoFavorite then call FailFast and returns ShowSnackbar in release`() = runTest {
        var throwableCaptured: Throwable? = null
        FailFast.setHandler { throwable, _ -> throwableCaptured = throwable }
        coEvery { serverManager.getServer() } returns null

        val event = createManager().execute("vehicle.test", EntityAddToAction.AndroidAutoFavorite)

        assertNotNull(throwableCaptured)
        coVerify(exactly = 0) { prefsRepository.addAutoFavorite(any()) }
        // In debug this won't be returned since FailFast would throw
        assertEquals(FrontendEvent.ShowSnackbar(commonR.string.add_to_android_auto_success), event)
    }

    @Test
    fun `Given null server when getting actionsForEntity then return empty list`() = runTest {
        coEvery { serverManager.getServer() } returns null

        val actions = createManager().getActionsForEntity("light.test")

        assertEquals(emptyList<ExternalEntityAddToAction>(), actions)
    }

    @Test
    fun `Given entity returns null when getting actionsForEntity then return empty list`() = runTest {
        coEvery { integrationRepository.getEntity("light.nonexistent") } returns null

        val actions = createManager().getActionsForEntity("light.nonexistent")

        assertEquals(emptyList<ExternalEntityAddToAction>(), actions)
    }

    @Test
    fun `Given entity throws when getting actionsForEntity then return empty list`() = runTest {
        coEvery { integrationRepository.getEntity(any()) } throws RuntimeException("Not found")

        val actions = createManager().getActionsForEntity("light.nonexistent")

        assertEquals(emptyList<ExternalEntityAddToAction>(), actions)
    }

    @Test
    fun `Given standard entityId when getting actionsForEntity then returns EntityWidget`() = runTest {
        val entityId = "automation.test"
        mockGetEntity(entityId)

        val actions = createManager().getActionsForEntity(entityId)

        assertEquals(listOf(EntityAddToAction.EntityWidget), actions.unwrap())
    }

    @Test
    fun `Given supported entity and free tile slot when getting actionsForEntity then returns Tile`() = runTest {
        val entityId = "script.test"
        mockGetEntity(entityId)
        coEvery { tileDao.getAll() } returns configuredTilesForAllSlots().drop(1)

        val actions = createManager().getActionsForEntity(entityId)

        assertTrue(EntityAddToAction.Tile in actions.unwrap())
    }

    @Test
    fun `Given unsupported SDK when getting actionsForEntity then does not return Tile`() = runTest {
        val entityId = "script.test"
        mockGetEntity(entityId)
        coEvery { tileDao.getAll() } returns configuredTilesForAllSlots().drop(1)
        SdkVersion.sdkInt = Build.VERSION_CODES.M

        val actions = createManager().getActionsForEntity(entityId)

        assertFalse(EntityAddToAction.Tile in actions.unwrap())
    }

    @Test
    fun `Given all tile slots configured when getting actionsForEntity then does not return Tile`() = runTest {
        val entityId = "script.test"
        mockGetEntity(entityId)
        coEvery { tileDao.getAll() } returns configuredTilesForAllSlots()

        val actions = createManager().getActionsForEntity(entityId)

        assertFalse(EntityAddToAction.Tile in actions.unwrap())
    }

    @Test
    fun `Given unsupported entity and free tile slot when getting actionsForEntity then does not return Tile`() = runTest {
        val entityId = "$CAMERA_DOMAIN.test"
        mockGetEntity(entityId)
        coEvery { tileDao.getAll() } returns configuredTilesForAllSlots().drop(1)

        val actions = createManager().getActionsForEntity(entityId)

        assertFalse(EntityAddToAction.Tile in actions.unwrap())
    }

    @Test
    fun `Given tile row exists but is not setup when getting actionsForEntity then returns Tile`() = runTest {
        val entityId = "script.test"
        mockGetEntity(entityId)
        coEvery { tileDao.getAll() } returns listOf(unconfiguredTile("tile_1")) + configuredTilesForAllSlots().drop(1)

        val actions = createManager().getActionsForEntity(entityId)

        assertTrue(EntityAddToAction.Tile in actions.unwrap())
    }

    @Test
    fun `Given alarm_control_panel entityId on full flavor when getting actionsForEntity then returns EntityWidget and AndroidAutoFavorite`() = runTest {
        val entityId = "alarm_control_panel.test"
        mockGetEntity(entityId)

        val actions = createManager(isFullFlavor = true).getActionsForEntity(entityId)

        assertEquals(listOf(EntityAddToAction.EntityWidget, EntityAddToAction.AndroidAutoFavorite), actions.unwrap())
    }

    @Test
    fun `Given alarm_control_panel entityId on minimal flavor when getting actionsForEntity then returns EntityWidget`() = runTest {
        val entityId = "alarm_control_panel.test"
        mockGetEntity(entityId)

        val actions = createManager(isFullFlavor = false).getActionsForEntity(entityId)

        assertEquals(listOf(EntityAddToAction.EntityWidget), actions.unwrap())
    }

    @Test
    fun `Given media player entityId when getting actionsForEntity then returns EntityWidget and MediaPlayerWidget`() = runTest {
        val entityId = "$MEDIA_PLAYER_DOMAIN.test"
        mockGetEntity(entityId)

        val actions = createManager().getActionsForEntity(entityId)

        assertEquals(listOf(EntityAddToAction.EntityWidget, EntityAddToAction.MediaPlayerWidget), actions.unwrap())
    }

    @Test
    fun `Given todo entityId when getting actionsForEntity then returns EntityWidget and TodoWidget`() = runTest {
        val entityId = "$TODO_DOMAIN.test"
        mockGetEntity(entityId)

        val actions = createManager().getActionsForEntity(entityId)

        assertEquals(listOf(EntityAddToAction.EntityWidget, EntityAddToAction.TodoWidget), actions.unwrap())
    }

    @Test
    fun `Given camera entityId when getting actionsForEntity then returns EntityWidget and CameraWidget`() = runTest {
        val entityId = "$CAMERA_DOMAIN.test"
        mockGetEntity(entityId)

        val actions = createManager().getActionsForEntity(entityId)

        assertEquals(listOf(EntityAddToAction.EntityWidget, EntityAddToAction.CameraWidget), actions.unwrap())
    }

    @Test
    fun `Given image entityId when getting actionsForEntity then returns EntityWidget and CameraWidget`() = runTest {
        val entityId = "$IMAGE_DOMAIN.test"
        mockGetEntity(entityId)

        val actions = createManager().getActionsForEntity(entityId)

        assertEquals(listOf(EntityAddToAction.EntityWidget, EntityAddToAction.CameraWidget), actions.unwrap())
    }

    @Test
    fun `Given EntityWidget action when executing then returns NavigateToWidgetConfig with Entity type`() = runTest {
        val event = createManager().execute("light.test", EntityAddToAction.EntityWidget)

        assertEquals(FrontendEvent.NavigateToWidgetConfig("light.test", WidgetType.Entity), event)
    }

    @Test
    fun `Given MediaPlayerWidget action when executing then returns NavigateToWidgetConfig with MediaPlayer type`() = runTest {
        val event = createManager().execute("media_player.tv", EntityAddToAction.MediaPlayerWidget)

        assertEquals(FrontendEvent.NavigateToWidgetConfig("media_player.tv", WidgetType.MediaPlayer), event)
    }

    @Test
    fun `Given CameraWidget action when executing then returns NavigateToWidgetConfig with Camera type`() = runTest {
        val event = createManager().execute("camera.front", EntityAddToAction.CameraWidget)

        assertEquals(FrontendEvent.NavigateToWidgetConfig("camera.front", WidgetType.Camera), event)
    }

    @Test
    fun `Given TodoWidget action when executing then returns NavigateToWidgetConfig with Todo type`() = runTest {
        val event = createManager().execute("todo.shopping", EntityAddToAction.TodoWidget)

        assertEquals(FrontendEvent.NavigateToWidgetConfig("todo.shopping", WidgetType.Todo), event)
    }

    @Test
    fun `Given Tile action when executing then returns NavigateToTileConfig`() = runTest {
        val event = createManager().execute("light.test", EntityAddToAction.Tile)

        assertEquals(FrontendEvent.NavigateToTileConfig("light.test"), event)
    }

    @Test
    fun `Given Shortcut action when executing then returns null`() = runTest {
        assertNull(createManager().execute("light.test", EntityAddToAction.Shortcut(enabled = true)))
    }

    @Test
    fun `Given Watch action when executing then returns null`() = runTest {
        assertNull(createManager().execute("light.test", EntityAddToAction.Watch(name = "Pixel Watch", enabled = true)))
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "true,light.test",
            "false,light.test",
            "true,alarm_control_panel.test",
            "false,alarm_control_panel.test",
        ],
    )
    fun `Given standard entity on automotive when getting actionsForEntity then returns auto favorite`(isFullFlavor: Boolean, entityId: String) = runTest {
        mockGetEntity(entityId)

        val actions = createManager(isAutomotive = true, isFullFlavor = isFullFlavor).getActionsForEntity(entityId)

        assertEquals(listOf(EntityAddToAction.AndroidAutoFavorite), actions.unwrap())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "$MEDIA_PLAYER_DOMAIN.test",
            "$TODO_DOMAIN.test",
            "$CAMERA_DOMAIN.test",
            "$IMAGE_DOMAIN.test",
        ],
    )
    fun `Given entity on automotive not vehicle domain when getting actionsForEntity then returns empty list`(entityId: String) = runTest {
        mockGetEntity(entityId)

        val actions = createManager(isAutomotive = true).getActionsForEntity(entityId)

        assertEquals(emptyList<ExternalEntityAddToAction>(), actions)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "light.test",
            "alarm_control_panel.test",
            "$MEDIA_PLAYER_DOMAIN.test",
            "$TODO_DOMAIN.test",
            "$CAMERA_DOMAIN.test",
            "$IMAGE_DOMAIN.test",
        ],
    )
    fun `Given entity on Quest when getting actionsForEntity then returns empty list`(entityId: String) = runTest {
        mockGetEntity(entityId)

        val actions = createManager(isQuest = true, isFullFlavor = false).getActionsForEntity(entityId)

        assertEquals(emptyList<ExternalEntityAddToAction>(), actions)
    }

    private fun createManager(
        isAutomotive: Boolean = false,
        isQuest: Boolean = false,
        isFullFlavor: Boolean = true,
    ) = FrontendEntityAddToManager(
        context = context,
        serverManager = serverManager,
        prefsRepository = prefsRepository,
        tileDao = tileDao,
        isAutomotive = isAutomotive,
        isQuest = isQuest,
        isFullFlavor = isFullFlavor,
    )

    private fun mockGetEntity(entityId: String) {
        coEvery { integrationRepository.getEntity(entityId) } coAnswers {
            delay(1)
            createEntity(firstArg<String>())
        }
    }

    private fun createEntity(entityId: String): Entity = Entity(entityId = entityId, "", mapOf(), LocalDateTime.now(), LocalDateTime.now())

    private fun configuredTile(tileId: String) = TileEntity(
        tileId = tileId,
        added = true,
        serverId = serverId,
        iconName = null,
        entityId = "light.test",
        label = "Light",
        subtitle = null,
        shouldVibrate = false,
        authRequired = false,
    )

    private fun unconfiguredTile(tileId: String) = configuredTile(tileId).copy(entityId = "", label = "")

    private fun configuredTilesForAllSlots() = tileSlots.map { configuredTile(it.id.value) }

    private fun List<ExternalEntityAddToAction>.unwrap(): List<EntityAddToAction> = map { ExternalEntityAddToAction.appPayloadToAction(it.appPayload) }
}
