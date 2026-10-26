package com.asistente.ia

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.opengl.Matrix as GlMatrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.Locale

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback, TextToSpeech.OnInitListener {

    companion object {
        init {
            Gltfio.init()
            Filament.init()
        }
        private const val PREFS_NAME = "AsistentePrefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENDPOINT = "api_endpoint"
        private const val KEY_MODEL = "api_model"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var uiHelper: UiHelper
    private lateinit var choreographer: Choreographer

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private var skybox: Skybox? = null

    private var swapChain: SwapChain? = null
    private var materialProvider: UbershaderProvider? = null
    private var assetLoader: AssetLoader? = null
    private var filamentAsset: FilamentAsset? = null
    private var isRendering = false

    // Control de animación procedimental
    private var baseTimeNanos: Long = 0L
    private var rotationAngle = 0.0f
    private var lastTouchX = 0.0f

    // UI
    private lateinit var bottomBar: LinearLayout
    private lateinit var txtResponse: TextView
    private lateinit var inputMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnSettings: ImageButton
    private var tts: TextToSpeech? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        bottomBar = findViewById(R.id.bottomBar)
        txtResponse = findViewById(R.id.txtResponse)
        inputMessage = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        btnSettings = findViewById(R.id.btnSettings)

        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBars.bottom + 16)
            insets
        }

        tts = TextToSpeech(this, this)
        choreographer = Choreographer.getInstance()

        initFilament()
        setupTouchControl()

        btnSettings.setOnClickListener { showApiKeyDialog() }
        btnSend.setOnClickListener { sendMessage() }
    }

    private fun initFilament() {
        engine = Engine.create(Engine.Backend.OPENGL)
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(engine.entityManager.create())

        val clearOptions = renderer.clearOptions
        clearOptions.clear = true
        renderer.clearOptions = clearOptions

        skybox = Skybox.Builder()
            .color(0.10f, 0.10f, 0.12f, 1.0f)
            .build(engine)
        scene.skybox = skybox

        view.scene = scene
        view.camera = camera
        view.isPostProcessingEnabled = false

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    swapChain?.let { engine.destroySwapChain(it) }
                    swapChain = engine.createSwapChain(surface)
                    view.viewport = Viewport(0, 0, surfaceView.width, surfaceView.height)
                }

                override fun onDetachedFromSurface() {
                    swapChain?.let {
                        engine.destroySwapChain(it)
                        engine.flushAndWait()
                        swapChain = null
                    }
                }

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
        camera.setProjection(40.0, aspect, 0.1, 20.0, Camera.Fov.VERTICAL)
        camera.lookAt(0.0, 1.40, 2.0, 0.0, 1.30, 0.0, 0.0, 1.0, 0.0)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchControl() {
        surfaceView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - lastTouchX
                    lastTouchX = event.x
                    rotationAngle += deltaX * 0.5f
                    true
                }
                else -> false
            }
        }
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

                applyRestingArmPose(asset)
            }
        }
    }

    private fun applyRestingArmPose(asset: FilamentAsset) {
        val tm = engine.transformManager

        // Brazo izquierdo: rotación hacia abajo en Z (-65°)
        rotateBone(asset, tm, "J_Bip_L_UpperArm", -65f, 0f, 0f, 1f)
        rotateBone(asset, tm, "J_Bip_L_LowerArm", -15f, 0f, 1f, 0f)

        // Brazo derecho: rotación hacia abajo en Z (+65°)
        rotateBone(asset, tm, "J_Bip_R_UpperArm", 65f, 0f, 0f, 1f)
        rotateBone(asset, tm, "J_Bip_R_LowerArm", 15f, 0f, 1f, 0f)

        // Actualizar matrices esqueléticas a través de la instancia
        asset.getInstance()?.getAnimator()?.updateBoneMatrices()
    }

    private fun rotateBone(
        asset: FilamentAsset,
        tm: TransformManager,
        boneName: String,
        angleDeg: Float,
        axisX: Float,
        axisY: Float,
        axisZ: Float
    ) {
        val entity = asset.getFirstEntityByName(boneName)
        if (entity != 0) {
            val instance = tm.getInstance(entity)
            if (instance != 0) {
                val originalTransform = FloatArray(16)
                tm.getTransform(instance, originalTransform)

                val rotMatrix = FloatArray(16)
                GlMatrix.setRotateM(rotMatrix, 0, angleDeg, axisX, axisY, axisZ)

                val newTransform = FloatArray(16)
                GlMatrix.multiplyMM(newTransform, 0, originalTransform, 0, rotMatrix, 0)

                tm.setTransform(instance, newTransform)
            }
        }
    }

    private fun updateCharacterProceduralTransform(angleYDeg: Float, offsetY: Float, tiltZDeg: Float) {
        filamentAsset?.let { asset ->
            val radY = Math.toRadians(angleYDeg.toDouble())
            val cy = Math.cos(radY).toFloat()
            val sy = Math.sin(radY).toFloat()

            val radZ = Math.toRadians(tiltZDeg.toDouble())
            val cz = Math.cos(radZ).toFloat()
            val sz = Math.sin(radZ).toFloat()

            val transform = floatArrayOf(
                cy * cz, sz, -sy * cz, 0f,
                -cy * sz, cz,  sy * sz, 0f,
                sy,       0f,  cy,      0f,
                0f, offsetY,   0f,      1f
            )

            val tm = engine.transformManager
            val instance = tm.getInstance(asset.root)
            if (instance != 0) {
                tm.setTransform(instance, transform)
            }
        }
    }

    private fun showApiKeyDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val inputKey = EditText(this).apply {
            hint = "Pega tu API Key (sk-...)"
            setText(prefs.getString(KEY_API_KEY, ""))
        }

        val radioGroup = RadioGroup(this)
        val rbOpenRouter = RadioButton(this).apply { text = "OpenRouter"; id = 1 }
        val rbGrok = RadioButton(this).apply { text = "xAI (Grok)"; id = 2 }
        radioGroup.addView(rbOpenRouter)
        radioGroup.addView(rbGrok)

        val currentEndpoint = prefs.getString(KEY_ENDPOINT, "openrouter")
        if (currentEndpoint == "grok") rbGrok.isChecked = true else rbOpenRouter.isChecked = true

        val inputModel = EditText(this).apply {
            hint = "Modelo (ej: openrouter/free)"
            val defaultM = if (currentEndpoint == "grok") "grok-beta" else "openrouter/free"
            setText(prefs.getString(KEY_MODEL, defaultM))
        }

        layout.addView(TextView(this).apply { text = "Clave de API:" })
        layout.addView(inputKey)
        layout.addView(TextView(this).apply { text = "Proveedor:" })
        layout.addView(radioGroup)
        layout.addView(TextView(this).apply { text = "Nombre del modelo:" })
        layout.addView(inputModel)

        AlertDialog.Builder(this)
            .setTitle("Configurar Asistente IA")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val endpoint = if (rbGrok.isChecked) "grok" else "openrouter"
                var model = inputModel.text.toString().trim()
                if (model.isEmpty()) {
                    model = if (endpoint == "grok") "grok-beta" else "openrouter/free"
                }
                prefs.edit()
                    .putString(KEY_API_KEY, inputKey.text.toString().trim())
                    .putString(KEY_ENDPOINT, endpoint)
                    .putString(KEY_MODEL, model)
                    .apply()
                Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun sendMessage() {
        val text = inputMessage.text.toString().trim()
        if (text.isEmpty()) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val endpoint = prefs.getString(KEY_ENDPOINT, "openrouter") ?: "openrouter"
        val modelName = prefs.getString(KEY_MODEL, if (endpoint == "grok") "grok-beta" else "openrouter/free") ?: "openrouter/free"

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Por favor configura tu API Key primero", Toast.LENGTH_LONG).show()
            showApiKeyDialog()
            return
        }

        inputMessage.setText("")
        txtResponse.text = "Pensando..."

        Thread {
            try {
                val apiUrl = if (endpoint == "grok") {
                    "https://api.x.ai/v1/chat/completions"
                } else {
                    "https://openrouter.ai/api/v1/chat/completions"
                }

                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (endpoint == "openrouter") {
                    conn.setRequestProperty("HTTP-Referer", "https://asistente.ia")
                }
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 20000

                val payload = JSONObject().apply {
                    put("model", modelName)
                    val messages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "Eres una asistente virtual anime alegre y servicial. Responde brevemente en español (máximo 2 párrafos).")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", text)
                        })
                    }
                    put("messages", messages)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseStr)
                    val reply = jsonResponse.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")

                    runOnUiThread {
                        txtResponse.text = reply
                        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "UtteranceId")
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error $responseCode"
                    runOnUiThread { txtResponse.text = "Error ($responseCode): $err" }
                }
            } catch (e: Exception) {
                runOnUiThread { txtResponse.text = "Error de red: ${e.message}" }
            }
        }.start()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "ES")
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)

        if (baseTimeNanos == 0L) baseTimeNanos = frameTimeNanos
        val elapsedSec = (frameTimeNanos - baseTimeNanos) / 1_000_000_000.0

        val breathOffsetY = (Math.sin(elapsedSec * 2.2) * 0.007).toFloat()
        val swayZDeg = (Math.sin(elapsedSec * 1.1) * 0.7).toFloat()

        updateCharacterProceduralTransform(rotationAngle, breathOffsetY, swayZDeg)
        filamentAsset?.getInstance()?.getAnimator()?.updateBoneMatrices()

        val currentSwapChain = swapChain
        if (uiHelper.isReadyToRender && currentSwapChain != null) {
            if (renderer.beginFrame(currentSwapChain, frameTimeNanos)) {
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
        tts?.stop()
        tts?.shutdown()
        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it) }
        filamentAsset?.let { assetLoader?.destroyAsset(it) }
        materialProvider?.destroy()
        assetLoader?.destroy()
        skybox?.let { engine.destroySkybox(it) }
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        engine.destroy()
    }
}
