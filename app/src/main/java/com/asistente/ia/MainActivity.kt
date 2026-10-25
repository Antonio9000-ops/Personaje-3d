package com.asistente.ia

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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
        private const val KEY_ENDPOINT = "api_endpoint" // openrouter o grok
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var uiHelper: UiHelper
    private lateinit var choreographer: Choreographer

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera

    private var swapChain: SwapChain? = null
    private var materialProvider: UbershaderProvider? = null
    private var assetLoader: AssetLoader? = null
    private var filamentAsset: FilamentAsset? = null
    private var isRendering = false

    // Control táctil de rotación
    private var rotationAngle = 0.0f
    private var lastTouchX = 0.0f

    // UI y Voz
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
        txtResponse = findViewById(R.id.txtResponse)
        inputMessage = findViewById(R.id.inputMessage)
        btnSend = findViewById(R.id.btnSend)
        btnSettings = findViewById(R.id.btnSettings)

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
        // Encuadre ajustado: distancia 2.1m, enfocado a la altura del rostro (1.40m)
        camera.setProjection(40.0, aspect, 0.1, 20.0, Camera.Fov.VERTICAL)
        camera.lookAt(0.0, 1.45, 2.1, 0.0, 1.35, 0.0, 0.0, 1.0, 0.0)
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
                    applyCharacterRotation(rotationAngle)
                    true
                }
                else -> false
            }
        }
    }

    private fun applyCharacterRotation(angleDegrees: Float) {
        filamentAsset?.let { asset ->
            val rad = Math.toRadians(angleDegrees.toDouble())
            val c = Math.cos(rad).toFloat()
            val s = Math.sin(rad).toFloat()
            // Matriz de rotación en el eje Y (col-major)
            val transform = floatArrayOf(
                c, 0f, -s, 0f,
                0f, 1f,  0f, 0f,
                s, 0f,  c, 0f,
                0f, 0f,  0f, 1f
            )
            val tm = engine.transformManager
            val instance = tm.getInstance(asset.root)
            tm.setTransform(instance, transform)
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

        layout.addView(inputKey)
        layout.addView(radioGroup)

        AlertDialog.Builder(this)
            .setTitle("Configurar IA")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val endpoint = if (rbGrok.isChecked) "grok" else "openrouter"
                prefs.edit()
                    .putString(KEY_API_KEY, inputKey.text.toString().trim())
                    .putString(KEY_ENDPOINT, endpoint)
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

                val modelName = if (endpoint == "grok") "grok-beta" else "deepseek/deepseek-chat"

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
                    runOnUiThread { txtResponse.text = "Error API ($responseCode): $err" }
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
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        engine.destroy()
    }
}
