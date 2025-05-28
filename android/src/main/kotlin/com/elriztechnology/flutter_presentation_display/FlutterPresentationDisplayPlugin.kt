package com.elriztechnology.flutter_presentation_display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import com.google.gson.Gson
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import org.json.JSONObject

class FlutterPresentationDisplayPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {

  private lateinit var channel: MethodChannel
  private lateinit var eventChannel: EventChannel
  private var flutterEngineChannel: MethodChannel? = null
  private var context: Context? = null
  private var presentation: PresentationDisplay? = null
  private var flutterBinding: FlutterPlugin.FlutterPluginBinding? = null

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, secondaryViewTypeId)
    channel.setMethodCallHandler(this)

    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, viewTypeEventsId)
    displayManager = flutterPluginBinding.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val displayConnectedStreamHandler = DisplayConnectedStreamHandler(displayManager)
    eventChannel.setStreamHandler(displayConnectedStreamHandler)
    flutterBinding = flutterPluginBinding
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "showPresentation" -> {
        try {
          val obj = JSONObject(call.arguments as String)
          Log.i("Plugin", "Method: ${call.method}, Arguments: ${call.arguments}")
          val displayId: Int = obj.getInt("displayId")
          val tag: String = obj.getString("routerName")
          val display = displayManager?.getDisplay(displayId)
          if (display != null) {
            val dataToMainCallback: (Any?) -> Unit = { argument ->
              flutterBinding?.let {
                MethodChannel(it.binaryMessenger, mainViewTypeId).invokeMethod("transferDataToMain", argument)
              }
            }

            val flutterEngine = createFlutterEngine(tag)
            flutterEngine?.let {
              flutterEngineChannel = MethodChannel(it.dartExecutor.binaryMessenger, secondaryViewTypeId)
              presentation = context?.let { context ->
                PresentationDisplay(context, tag, display, dataToMainCallback)
              }
              presentation?.show()
              result.success(true)
            } ?: run {
              result.error("404", "Can't find FlutterEngine", null)
            }
          } else {
            result.error("404", "Can't find display with displayId $displayId", null)
          }
        } catch (e: Exception) {
          result.error(call.method, e.message, null)
        }
      }

      "hidePresentation" -> {
        try {
          presentation?.dismiss()
          presentation = null
          result.success(true)
        } catch (e: Exception) {
          result.error(call.method, e.message, null)
        }
      }

      "listDisplay" -> {
        val displays = displayManager?.getDisplays(call.arguments as? String)
        val listJson = displays?.map { display ->
          DisplayModel(display.displayId, display.flags, display.rotation, display.name)
        }
        result.success(Gson().toJson(listJson))
      }

      "transferDataToPresentation" -> {
        try {
          flutterEngineChannel?.invokeMethod("transferDataToPresentation", call.arguments)
          result.success(true)
        } catch (e: Exception) {
          result.error("Error transferring data", e.message, null)
        }
      }

      else -> result.notImplemented()
    }
  }

/**
 * Crée (ou récupère) un FlutterEngine associé au tag donné.
 * Le moteur est lancé sans assertions (--disable-dart-asserts) afin
 * d’éviter l’activation automatique des flags debug-paint.
 */
private fun createFlutterEngine(tag: String): FlutterEngine? {
  return context?.let { ctx ->

    // 1) Essayons d'abord de récupérer un moteur déjà mis en cache
    val cache = FlutterEngineCache.getInstance()
    var engine = cache.get(tag)
    Log.i("Plugin", "createFlutterEngine: fromCache=${engine != null}")
    if (engine != null) return engine

    //FlutterEngineCache.getInstance().clear()     // ← vide tout
    //FlutterEngineCache.getInstance().put(tag, engine)

    // 2) Initialisation complète du FlutterLoader
    val loader = FlutterInjector.instance().flutterLoader()
    loader.startInitialization(ctx)
    loader.ensureInitializationComplete(          // version synchrone
      ctx,
      arrayOf("--disable-dart-asserts")           // désactive toutes les assertions
    )

    // 3) Création du moteur Flutter
    engine = FlutterEngine(ctx)
    engine.navigationChannel.setInitialRoute(tag)

    // 4) Lancement du point d’entrée Dart
    val entrypoint = DartExecutor.DartEntrypoint(
      loader.findAppBundlePath(),
      "secondaryDisplayMain"                      // doit correspondre au @pragma Dart
    )
    engine.dartExecutor.executeDartEntrypoint(entrypoint)
    engine.lifecycleChannel.appIsResumed()

    // 5) Mise en cache pour ré-utilisation
    FlutterEngineCache.getInstance().put(tag, engine)

    engine
  }
}




  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    context = binding.activity
    displayManager = context?.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
  }

  override fun onDetachedFromActivity() {
    context = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    context = binding.activity
  }

  override fun onDetachedFromActivityForConfigChanges() {
    context = null
  }

  companion object {
    private const val viewTypeEventsId = "presentation_display_channel_events"
    private const val secondaryViewTypeId = "presentation_display_channel"
    private const val mainViewTypeId = "main_display_channel"

    private var displayManager: DisplayManager? = null
  }
}


class DisplayConnectedStreamHandler(private val displayManager: DisplayManager?) : EventChannel.StreamHandler {

  private var sink: EventChannel.EventSink? = null
  private val displayListener = object : DisplayManager.DisplayListener {
    override fun onDisplayAdded(displayId: Int) {
      sink?.success(1)
    }

    override fun onDisplayRemoved(displayId: Int) {
      sink?.success(0)
    }

    override fun onDisplayChanged(displayId: Int) {}
  }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    sink = events
    displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
  }

  override fun onCancel(arguments: Any?) {
    sink = null
    displayManager?.unregisterDisplayListener(displayListener)
  }
}
