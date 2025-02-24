/*
 * Designed and developed by 2022 skydoves (Jaewoong Eum)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.skydoves.cloudy

import android.graphics.Bitmap
import android.renderscript.RenderScript
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.skydoves.cloudy.internals.render.iterativeBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * `Modifier.cloudy()` is a replacement of the [blur] modifier (compatible with under Android 12),
 * which blurs with the given [radius].
 *
 * History: The [blur] modifier supports only Android 12 and higher, and [RenderScript] was also deprecated.
 *
 * @param radius Radius of the blur along both the x and y axis.
 * @param enabled Enabling the blur effects.
 * @param graphicsLayer The graphic layer that records the original content and get the bitmap information.
 * This parameter should be used when you need to remain with the same graphic layer for the dynamically
 * updated Composable functions, such as Lazy Lists.
 * @param onStateChanged Lambda function that will be invoked when the blur process has been updated.
 */
@Composable
public fun Modifier.cloudy(
  radius: Int = 10,
  enabled: Boolean = true,
  onStateChanged: (CloudyState) -> Unit = {}
): Modifier {
  if (!enabled) {
    return this
  }

  // This local inspection preview only works over Android 12.
  if (LocalInspectionMode.current) {
    return this.blur(radius = radius.dp)
  }

  return this then CloudyModifierNodeElement(
    radius = radius,
    onStateChanged = onStateChanged
  )
}

private data class CloudyModifierNodeElement(
  val radius: Int = 10,
  val onStateChanged: (CloudyState) -> Unit = {}
) : ModifierNodeElement<CloudyModifierNode>() {

  override fun InspectorInfo.inspectableProperties() {
    name = "cloudy"
    properties["cloudy"] = radius
  }

  override fun create(): CloudyModifierNode = CloudyModifierNode(
    radius = radius,
    onStateChanged = onStateChanged
  )

  override fun update(node: CloudyModifierNode) {
    node.radius = radius
  }
}

private class CloudyModifierNode(
  var radius: Int = 10,
  private val onStateChanged: (CloudyState) -> Unit = {}
) : DrawModifierNode, Modifier.Node() {

  private var cachedOutput: Bitmap? by mutableStateOf(null)

  override fun ContentDrawScope.draw() {
    val graphicsLayer = requireGraphicsContext().createGraphicsLayer()

    // call record to capture the content in the graphics layer
    graphicsLayer.record {
      // draw the contents of the composable into the graphics layer
      this@draw.drawContent()
    }

    drawLayer(graphicsLayer)

    onStateChanged.invoke(CloudyState.Loading)

    coroutineScope.launch(Dispatchers.Main.immediate) {
      try {
        val targetBitmap: Bitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
          .copy(Bitmap.Config.ARGB_8888, true)

        val out =
          if (cachedOutput == null || cachedOutput?.width != targetBitmap.width || cachedOutput?.height != targetBitmap.height) {
            createCompatibleBitmap(targetBitmap).also { cachedOutput = it }
          } else {
            cachedOutput!!
          }

        val blurredBitmap = iterativeBlur(
          androidBitmap = targetBitmap,
          outputBitmap = out,
          radius = radius
        ).await()?.apply {
          drawImage(this.asImageBitmap())
        } ?: throw RuntimeException("Couldn't capture a bitmap from the composable tree")

        onStateChanged.invoke(CloudyState.Success(blurredBitmap))
      } catch (e: Exception) {
        Log.e("Test", "exception: $e")
        onStateChanged.invoke(CloudyState.Error(e))
      } finally {
        requireGraphicsContext().releaseGraphicsLayer(graphicsLayer)
      }
    }
  }
}

private fun createCompatibleBitmap(inputBitmap: Bitmap) =
  Bitmap.createBitmap(inputBitmap.width, inputBitmap.height, inputBitmap.config!!)
