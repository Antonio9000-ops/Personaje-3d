package com.asistente.ia

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Choreographer
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.channels.Channels

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {

    companion object {
        init {
            Gltfio.init()
            Filament.init()
        }
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var uiHelper: UiHelper
    private lateinit var choreographer: Choreographer

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera

    private var materialProvider: UbershaderProvider? = null
    private var assetLoader: AssetLoader? = null
    private var filamentAsset: FilamentAsset? = null
    private var isRendering = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        choreographer = Choreographer.getInstance()
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        initFilament()
    }

    private fun initFilament() {
        // Forzar backend OpenGL ES para evitar sobrecosto de memoria de Vulkan
        engine = Engine.create(Engine.Backend.OPENGL)
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(engine.entityManager.create())

        view.scene = scene
        view.camera = camera
        view.isPostProcessingEnabled = false // Ahorro de GPU en modelo anime unlit

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: android.view.Surface) {
                    val swapChain = engine.createSwapChain(surface)
                    renderer.setPresentationTime(0)
                    view.viewport = Viewport(0, 0, surfaceView.width, surfaceView.height)
                }

                override fun onDetachedFromSurface() {}

                override fun onResized(width: Int, height: Int) {
                    view.viewport = Viewport(0, 0, width, height)
                    updateCameraProjection(width, height)
                }
            }
            attachTo(surfaceView)
        }

        loadModelGlb("Personaje.glb")
    }

    private fun updateCameraProjection(width: Int, height: Int) {
        val aspect = width.toDouble() / height.toDouble()
        camera.setProjection(45.0, aspect, 0.1, 20.0, Camera.Fov.VERTICAL)
        camera.lookAt(0.0, 1.3, 1.2, 0.0, 1.2, 0.0, 0.0, 1.0, 0.0)
    }

    private fun loadModelGlb(assetName: String) {
        val provider = UbershaderProvider(engine)
        val loader = AssetLoader(engine, provider, EntityManager.get())
        this.materialProvider = provider
        this.assetLoader = loader

        assets.open(assetName).use { input ->
            val channel = Channels.newChannel(input)
            val buffer = ByteBuffer.allocateDirect(input.available())
            channel.read(buffer)
            buffer.rewind()

            filamentAsset = loader.createAsset(buffer)
            filamentAsset?.let { asset ->
                val resourceLoader = ResourceLoader(engine)
                resourceLoader.loadResources(asset)
                scene.addEntities(asset.entities)
                asset.releaseSourceData()
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)
        if (uiHelper.isReadyToRender) {
            val swapChain = uiHelper.swapChain
            if (swapChain != null && renderer.beginFrame(swapChain, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isRendering) {
            choreographer.postFrameCallback(this)
            isRendering = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRendering) {
            choreographer.removeFrameCallback(this)
            isRendering = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(this)
        uiHelper.detach()
        filamentAsset?.let { assetLoader?.destroyAsset(it) }
        materialProvider?.destroy()
        assetLoader?.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        engine.destroy()
    }
}
